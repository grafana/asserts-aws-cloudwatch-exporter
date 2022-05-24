/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.exporter;

import ai.asserts.aws.AWSClientProvider;
import ai.asserts.aws.AccountProvider;
import ai.asserts.aws.AccountProvider.AWSAccount;
import ai.asserts.aws.RateLimiter;
import ai.asserts.aws.resource.ResourceMapper;
import ai.asserts.aws.resource.ResourceRelation;
import com.google.common.collect.ImmutableSortedMap;
import io.prometheus.client.Collector;
import io.prometheus.client.Collector.MetricFamilySamples.Sample;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.autoscaling.AutoScalingClient;
import software.amazon.awssdk.services.autoscaling.model.AutoScalingGroup;
import software.amazon.awssdk.services.autoscaling.model.DescribeAutoScalingGroupsResponse;
import software.amazon.awssdk.services.autoscaling.model.DescribeTagsRequest;
import software.amazon.awssdk.services.autoscaling.model.DescribeTagsResponse;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

import static ai.asserts.aws.MetricNameUtil.SCRAPE_ACCOUNT_ID_LABEL;
import static ai.asserts.aws.MetricNameUtil.SCRAPE_OPERATION_LABEL;
import static ai.asserts.aws.MetricNameUtil.SCRAPE_REGION_LABEL;
import static org.springframework.util.CollectionUtils.isEmpty;

@Component
@Slf4j
public class LBToASGRelationBuilder extends Collector {
    private final AWSClientProvider awsClientProvider;
    private final ResourceMapper resourceMapper;
    private final TargetGroupLBMapProvider targetGroupLBMapProvider;
    private final RateLimiter rateLimiter;
    private final AccountProvider accountProvider;
    private final MetricSampleBuilder metricSampleBuilder;
    @Getter
    private volatile Set<ResourceRelation> routingConfigs = new HashSet<>();
    private volatile List<MetricFamilySamples> asgResourceMetrics = new ArrayList<>();

    public LBToASGRelationBuilder(AWSClientProvider awsClientProvider,
                                  ResourceMapper resourceMapper, TargetGroupLBMapProvider targetGroupLBMapProvider,
                                  RateLimiter rateLimiter, AccountProvider accountProvider,
                                  MetricSampleBuilder metricSampleBuilder) {
        this.awsClientProvider = awsClientProvider;
        this.resourceMapper = resourceMapper;
        this.targetGroupLBMapProvider = targetGroupLBMapProvider;
        this.rateLimiter = rateLimiter;
        this.accountProvider = accountProvider;
        this.metricSampleBuilder = metricSampleBuilder;
    }

    @Override
    public List<MetricFamilySamples> collect() {
        return asgResourceMetrics;
    }

    public void updateRouting() {
        log.info("Updating LB to ASG Routing relations");
        Set<ResourceRelation> newConfigs = new HashSet<>();
        List<MetricFamilySamples> newMetrics = new ArrayList<>();
        List<Sample> samples = new ArrayList<>();
        for (AWSAccount accountRegion : accountProvider.getAccounts()) {
            accountRegion.getRegions().forEach(region -> {
                String api = "AutoScalingClient/describeAutoScalingGroups";
                try (AutoScalingClient asgClient = rateLimiter.doWithRateLimit(api,
                        ImmutableSortedMap.of(
                                SCRAPE_ACCOUNT_ID_LABEL, accountRegion.getAccountId(),
                                SCRAPE_REGION_LABEL, region,
                                SCRAPE_OPERATION_LABEL, api
                        ),
                        () -> awsClientProvider.getAutoScalingClient(region, accountRegion))) {
                    DescribeAutoScalingGroupsResponse resp = asgClient.describeAutoScalingGroups();
                    List<AutoScalingGroup> groups = resp.autoScalingGroups();
                    if (!isEmpty(groups)) {
                        groups.forEach(asg -> resourceMapper.map(asg.autoScalingGroupARN()).ifPresent(asgRes -> {
                            Map<String, String> labels = new TreeMap<>();
                            labels.put(SCRAPE_ACCOUNT_ID_LABEL, accountRegion.getAccountId());
                            labels.put(SCRAPE_REGION_LABEL, region);
                            labels.put("namespace", "AWS/AutoScaling");
                            labels.put("aws_resource_type", "AWS::AutoScaling::AutoScalingGroup");
                            labels.put("job", asgRes.getName());
                            labels.put("name", asgRes.getName());

                            DescribeTagsResponse describeTagsResponse = asgClient.describeTags(DescribeTagsRequest.builder()
                                    .build());
                            describeTagsResponse.tags()
                                    .forEach(tagDescription -> {
                                        if (tagDescription.key().contains("k8s") || tagDescription.key().contains("kubernetes")) {
                                            labels.put("sub_type", "k8s");
                                        }
                                    });
                            samples.add(metricSampleBuilder.buildSingleSample("aws_resource", labels, 1.0D));

                            if (!isEmpty(asg.targetGroupARNs())) {
                                asg.targetGroupARNs().stream()
                                        .map(resourceMapper::map)
                                        .filter(Optional::isPresent)
                                        .map(Optional::get)
                                        .filter(tg -> targetGroupLBMapProvider.getTgToLB().containsKey(tg))
                                        .map(tg -> targetGroupLBMapProvider.getTgToLB().get(tg))
                                        .forEach(lb -> newConfigs.add(ResourceRelation.builder()
                                                .from(lb)
                                                .to(asgRes)
                                                .name("ROUTES_TO")
                                                .build()));
                            }
                        }));
                    }
                } catch (Exception e) {
                    log.error("Failed to build LB to ASG relationship", e);
                }
            });
        }

        if (samples.size() > 0) {
            newMetrics.add(metricSampleBuilder.buildFamily(samples));
        }

        routingConfigs = newConfigs;
        asgResourceMetrics = newMetrics;
    }
}
