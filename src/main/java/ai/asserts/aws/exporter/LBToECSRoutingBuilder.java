/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.exporter;

import ai.asserts.aws.RateLimiter;
import ai.asserts.aws.resource.Resource;
import ai.asserts.aws.resource.ResourceMapper;
import ai.asserts.aws.resource.ResourceRelation;
import com.google.common.collect.ImmutableSortedMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.ecs.EcsClient;
import software.amazon.awssdk.services.ecs.model.DescribeServicesRequest;
import software.amazon.awssdk.services.ecs.model.DescribeServicesResponse;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static ai.asserts.aws.MetricNameUtil.SCRAPE_ACCOUNT_ID_LABEL;
import static ai.asserts.aws.MetricNameUtil.SCRAPE_OPERATION_LABEL;
import static ai.asserts.aws.MetricNameUtil.SCRAPE_REGION_LABEL;

@Component
@Slf4j
public class LBToECSRoutingBuilder {
    private final RateLimiter rateLimiter;
    private final ResourceMapper resourceMapper;
    private final TargetGroupLBMapProvider targetGroupLBMapProvider;

    public LBToECSRoutingBuilder(RateLimiter rateLimiter, ResourceMapper resourceMapper,
                                 TargetGroupLBMapProvider targetGroupLBMapProvider) {
        this.rateLimiter = rateLimiter;
        this.resourceMapper = resourceMapper;
        this.targetGroupLBMapProvider = targetGroupLBMapProvider;
    }

    public Set<ResourceRelation> getRoutings(EcsClient ecsClient, Resource cluster, List<Resource> services) {
        Set<ResourceRelation> routing = new HashSet<>();
        log.info("Updating Resource Relation for ECS Cluster {}", cluster);
        if (services.size() > 0) {
            try {
                String api = "EcsClient/describeServices";
                DescribeServicesResponse response = rateLimiter.doWithRateLimit(api,
                        ImmutableSortedMap.of(
                                SCRAPE_REGION_LABEL, cluster.getRegion(),
                                SCRAPE_ACCOUNT_ID_LABEL, cluster.getAccount(),
                                SCRAPE_OPERATION_LABEL, api
                        )
                        , () -> ecsClient.describeServices(DescribeServicesRequest.builder()
                                .cluster(cluster.getArn())
                                .services(services.stream().map(Resource::getArn).collect(Collectors.toList()))
                                .build()));
                if (response.hasServices()) {
                    response.services().stream()
                            .filter(service -> resourceMapper.map(service.serviceArn()).isPresent()).forEach(service -> {
                        Optional<Resource> servResOpt = resourceMapper.map(service.serviceArn());
                        servResOpt.ifPresent(servRes -> routing.addAll(service.loadBalancers().stream()
                                .map(loadBalancer -> resourceMapper.map(loadBalancer.targetGroupArn()))
                                .filter(Optional::isPresent).map(Optional::get)
                                .map(tg -> targetGroupLBMapProvider.getTgToLB().get(tg))
                                .filter(Objects::nonNull)
                                .map(lb -> ResourceRelation.builder()
                                        .from(lb)
                                        .to(servRes)
                                        .name("ROUTES_TO")
                                        .build())
                                .collect(Collectors.toSet())));
                    });
                }
            } catch (Exception e) {
                log.error("Failed to build resource relations", e);
            }
        }
        return routing;
    }
}
