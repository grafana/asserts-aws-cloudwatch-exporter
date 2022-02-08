/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.exporter;

import ai.asserts.aws.AWSClientProvider;
import ai.asserts.aws.cloudwatch.config.ScrapeConfigProvider;
import ai.asserts.aws.resource.ResourceMapper;
import ai.asserts.aws.resource.ResourceRelation;
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

import static org.springframework.util.CollectionUtils.isEmpty;

@Component
@Slf4j
public class LBToASGRelationBuilder {
    private final ScrapeConfigProvider scrapeConfigProvider;
    private final AWSClientProvider awsClientProvider;
    private final ResourceMapper resourceMapper;
    private final TargetGroupLBMapProvider targetGroupLBMapProvider;
    @Getter
    private volatile Set<ResourceRelation> routingConfigs = new HashSet<>();

    public LBToASGRelationBuilder(ScrapeConfigProvider scrapeConfigProvider, AWSClientProvider awsClientProvider,
                                  ResourceMapper resourceMapper, TargetGroupLBMapProvider targetGroupLBMapProvider) {
        this.scrapeConfigProvider = scrapeConfigProvider;
        this.awsClientProvider = awsClientProvider;
        this.resourceMapper = resourceMapper;
        this.targetGroupLBMapProvider = targetGroupLBMapProvider;
    }

    public void updateRouting() {
        Set<ResourceRelation> newConfigs = new HashSet<>();
        scrapeConfigProvider.getScrapeConfig().getRegions().forEach(region -> {
            AutoScalingClient autoScalingClient = awsClientProvider.getAutoScalingClient(region);
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
        });
        routingConfigs = newConfigs;
    }
}
