/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.exporter;

import ai.asserts.aws.AWSClientProvider;
import ai.asserts.aws.RateLimiter;
import ai.asserts.aws.ScrapeConfigProvider;
import ai.asserts.aws.resource.ResourceMapper;
import ai.asserts.aws.resource.ResourceRelation;
import com.google.common.collect.ImmutableSortedMap;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.autoscaling.AutoScalingClient;
import software.amazon.awssdk.services.autoscaling.model.AutoScalingGroup;
import software.amazon.awssdk.services.autoscaling.model.DescribeAutoScalingGroupsResponse;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static ai.asserts.aws.MetricNameUtil.SCRAPE_OPERATION_LABEL;
import static ai.asserts.aws.MetricNameUtil.SCRAPE_REGION_LABEL;
import static org.springframework.util.CollectionUtils.isEmpty;

@Component
@Slf4j
public class LBToASGRelationBuilder {
    private final ScrapeConfigProvider scrapeConfigProvider;
    private final AWSClientProvider awsClientProvider;
    private final ResourceMapper resourceMapper;
    private final TargetGroupLBMapProvider targetGroupLBMapProvider;
    private final RateLimiter rateLimiter;
    @Getter
    private volatile Set<ResourceRelation> routingConfigs = new HashSet<>();

    public LBToASGRelationBuilder(ScrapeConfigProvider scrapeConfigProvider, AWSClientProvider awsClientProvider,
                                  ResourceMapper resourceMapper, TargetGroupLBMapProvider targetGroupLBMapProvider,
                                  RateLimiter rateLimiter) {
        this.scrapeConfigProvider = scrapeConfigProvider;
        this.awsClientProvider = awsClientProvider;
        this.resourceMapper = resourceMapper;
        this.targetGroupLBMapProvider = targetGroupLBMapProvider;
        this.rateLimiter = rateLimiter;
    }

    public void updateRouting() {
        log.info("Updating LB to ASG Routing relations");
        Set<ResourceRelation> newConfigs = new HashSet<>();
        scrapeConfigProvider.getScrapeConfig().getRegions().forEach(region -> {
            String api = "AutoScalingClient/describeAutoScalingGroups";
            try (AutoScalingClient autoScalingClient = rateLimiter.doWithRateLimit(api,
                    ImmutableSortedMap.of(
                            SCRAPE_REGION_LABEL, region, SCRAPE_OPERATION_LABEL, api
                    ),
                    () -> awsClientProvider.getAutoScalingClient(region))) {
                DescribeAutoScalingGroupsResponse resp = autoScalingClient.describeAutoScalingGroups();
                List<AutoScalingGroup> groups = resp.autoScalingGroups();
                if (!isEmpty(groups)) {
                    groups.forEach(asg -> resourceMapper.map(asg.autoScalingGroupARN()).ifPresent(asgRes -> {
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
        routingConfigs = newConfigs;
    }
}
