/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.exporter;

import ai.asserts.aws.AWSClientProvider;
import ai.asserts.aws.CollectionBuilderTask;
import ai.asserts.aws.AWSApiCallRateLimiter;
import ai.asserts.aws.ScrapeConfigProvider;
import ai.asserts.aws.TagUtil;
import ai.asserts.aws.TaskExecutorUtil;
import ai.asserts.aws.account.AWSAccount;
import ai.asserts.aws.account.AccountProvider;
import ai.asserts.aws.config.ScrapeConfig;
import ai.asserts.aws.resource.ResourceMapper;
import ai.asserts.aws.resource.ResourceRelation;
import com.google.common.collect.ImmutableSortedMap;
import io.prometheus.client.Collector;
import io.prometheus.client.Collector.MetricFamilySamples.Sample;
import io.prometheus.client.CollectorRegistry;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.autoscaling.AutoScalingClient;
import software.amazon.awssdk.services.autoscaling.model.AutoScalingGroup;
import software.amazon.awssdk.services.autoscaling.model.DescribeAutoScalingGroupsResponse;
import software.amazon.awssdk.services.autoscaling.model.DescribeTagsResponse;
import software.amazon.awssdk.services.resourcegroupstaggingapi.model.Tag;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Future;

import static ai.asserts.aws.MetricNameUtil.SCRAPE_ACCOUNT_ID_LABEL;
import static ai.asserts.aws.MetricNameUtil.SCRAPE_OPERATION_LABEL;
import static ai.asserts.aws.MetricNameUtil.SCRAPE_REGION_LABEL;
import static org.springframework.util.CollectionUtils.isEmpty;

@Component
@Slf4j
public class LBToASGRelationBuilder extends Collector implements InitializingBean {
    private final AWSClientProvider awsClientProvider;
    private final ResourceMapper resourceMapper;
    private final TargetGroupLBMapProvider targetGroupLBMapProvider;
    private final AWSApiCallRateLimiter rateLimiter;
    private final AccountProvider accountProvider;
    private final MetricSampleBuilder metricSampleBuilder;
    private final CollectorRegistry collectorRegistry;
    private final TagUtil tagUtil;
    private final TaskExecutorUtil taskExecutorUtil;
    private final ScrapeConfigProvider scrapeConfigProvider;
    @Getter
    private volatile Set<ResourceRelation> routingConfigs = new HashSet<>();
    private volatile List<MetricFamilySamples> asgResourceMetrics = new ArrayList<>();

    public LBToASGRelationBuilder(AWSClientProvider awsClientProvider,
                                  ResourceMapper resourceMapper, TargetGroupLBMapProvider targetGroupLBMapProvider,
                                  AWSApiCallRateLimiter rateLimiter, AccountProvider accountProvider,
                                  MetricSampleBuilder metricSampleBuilder, CollectorRegistry collectorRegistry,
                                  TagUtil tagUtil, TaskExecutorUtil taskExecutorUtil,
                                  ScrapeConfigProvider scrapeConfigProvider) {
        this.awsClientProvider = awsClientProvider;
        this.resourceMapper = resourceMapper;
        this.targetGroupLBMapProvider = targetGroupLBMapProvider;
        this.rateLimiter = rateLimiter;
        this.accountProvider = accountProvider;
        this.metricSampleBuilder = metricSampleBuilder;
        this.collectorRegistry = collectorRegistry;
        this.tagUtil = tagUtil;
        this.taskExecutorUtil = taskExecutorUtil;
        this.scrapeConfigProvider = scrapeConfigProvider;
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        collectorRegistry.register(this);
    }

    @Override
    public List<MetricFamilySamples> collect() {
        return asgResourceMetrics;
    }

    public void updateRouting() {
        log.info("Updating LB to ASG Routing relations");
        Set<ResourceRelation> newConfigs = new HashSet<>();
        List<MetricFamilySamples> newMetrics = new ArrayList<>();
        List<Sample> allSamples = new ArrayList<>();
        List<Future<List<Sample>>> futures = new ArrayList<>();
        for (AWSAccount accountRegion : accountProvider.getAccounts()) {
            accountRegion.getRegions().forEach(region ->
                    futures.add(taskExecutorUtil.executeAccountTask(accountRegion,
                            new CollectionBuilderTask<Sample>() {
                                @Override
                                public List<Sample> call() {
                                    return buildSamples(region, accountRegion, allSamples, newConfigs);
                                }
                            })));
        }
        taskExecutorUtil.awaitAll(futures, allSamples::addAll);
        if (allSamples.size() > 0) {
            metricSampleBuilder.buildFamily(allSamples).ifPresent(newMetrics::add);
        }
        routingConfigs = newConfigs;
        asgResourceMetrics = newMetrics;
    }

    private List<Sample> buildSamples(String region, AWSAccount accountRegion, List<Sample> allSamples,
                                      Set<ResourceRelation> newConfigs) {
        ScrapeConfig scrapeConfig = scrapeConfigProvider.getScrapeConfig(accountRegion.getTenant());
        List<Sample> samples = new ArrayList<>();
        try {
            AutoScalingClient asgClient = awsClientProvider.getAutoScalingClient(region, accountRegion);
            DescribeAutoScalingGroupsResponse resp = rateLimiter.doWithRateLimit(
                    "AutoScalingClient/describeAutoScalingGroups",
                    ImmutableSortedMap.of(
                            SCRAPE_ACCOUNT_ID_LABEL, accountRegion.getAccountId(),
                            SCRAPE_REGION_LABEL, region,
                            SCRAPE_OPERATION_LABEL, "AutoScalingClient/describeAutoScalingGroups"
                    ),
                    asgClient::describeAutoScalingGroups);
            List<AutoScalingGroup> groups = resp.autoScalingGroups();
            if (!isEmpty(groups)) {
                DescribeTagsResponse describeTagsResponse = rateLimiter.doWithRateLimit(
                        "AutoScalingClient/describeTags",
                        ImmutableSortedMap.of(
                                SCRAPE_ACCOUNT_ID_LABEL, accountRegion.getAccountId(),
                                SCRAPE_REGION_LABEL, region,
                                SCRAPE_OPERATION_LABEL, "AutoScalingClient/describeTags"
                        ),
                        asgClient::describeTags);

                Map<String, List<Tag>>
                        tagLabelsByName = new TreeMap<>();

                describeTagsResponse.tags()
                        .stream()
                        .filter(td -> "auto-scaling-group".equals(td.resourceType()))
                        .forEach(td -> tagLabelsByName.computeIfAbsent(td.resourceId(), k -> new ArrayList<>())
                                .add(Tag
                                        .builder().key(td.key()).value(td.value()).build()));

                groups.forEach(asg -> resourceMapper.map(asg.autoScalingGroupARN()).ifPresent(asgRes -> {
                    // Only discover Non k8s ASGs. K8S ASGs will be discovered through other means
                    Map<String, String> tagLabels =
                            tagUtil.tagLabels(scrapeConfig, tagLabelsByName.getOrDefault(asg.autoScalingGroupName(),
                                    Collections.emptyList()));

                    if (tagLabels.keySet().stream().noneMatch(key -> key.contains("k8s"))) {
                        Map<String, String> labels = new TreeMap<>();
                        labels.put(SCRAPE_ACCOUNT_ID_LABEL, accountRegion.getAccountId());
                        labels.put(SCRAPE_REGION_LABEL, region);
                        labels.put("namespace", "AWS/AutoScaling");
                        labels.put("aws_resource_type", "AWS::AutoScaling::AutoScalingGroup");
                        labels.put("job", asgRes.getName());
                        labels.put("id", asgRes.getId());
                        labels.put("name", asgRes.getName());
                        labels.putAll(tagLabels);
                        Optional<Sample> opt =
                                metricSampleBuilder.buildSingleSample("aws_resource", labels, 1.0D);
                        opt.ifPresent(allSamples::add);
                    }

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
            log.error("Failed to build LB to ASG relationship for " + accountRegion, e);
        }
        return samples;
    }
}
