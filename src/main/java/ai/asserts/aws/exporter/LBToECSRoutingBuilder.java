/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.exporter;

import ai.asserts.aws.AWSClientProvider;
import ai.asserts.aws.AccountProvider;
import ai.asserts.aws.RateLimiter;
import ai.asserts.aws.resource.Resource;
import ai.asserts.aws.resource.ResourceMapper;
import ai.asserts.aws.resource.ResourceRelation;
import com.google.common.collect.ImmutableSortedMap;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.ecs.EcsClient;
import software.amazon.awssdk.services.ecs.model.DescribeServicesRequest;
import software.amazon.awssdk.services.ecs.model.DescribeServicesResponse;
import software.amazon.awssdk.services.ecs.model.ListServicesRequest;
import software.amazon.awssdk.services.ecs.model.ListServicesResponse;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static ai.asserts.aws.MetricNameUtil.SCRAPE_ACCOUNT_ID_LABEL;
import static ai.asserts.aws.MetricNameUtil.SCRAPE_OPERATION_LABEL;
import static ai.asserts.aws.MetricNameUtil.SCRAPE_REGION_LABEL;

@Component
@Slf4j
public class LBToECSRoutingBuilder implements Runnable {
    private final RateLimiter rateLimiter;
    private final ResourceMapper resourceMapper;
    private final TargetGroupLBMapProvider targetGroupLBMapProvider;

    private final AWSClientProvider awsClientProvider;

    private final AccountProvider accountProvider;

    @Getter
    private volatile Set<ResourceRelation> routing = new HashSet<>();

    public LBToECSRoutingBuilder(RateLimiter rateLimiter, ResourceMapper resourceMapper,
                                 TargetGroupLBMapProvider targetGroupLBMapProvider,
                                 AWSClientProvider awsClientProvider, AccountProvider accountProvider) {
        this.rateLimiter = rateLimiter;
        this.resourceMapper = resourceMapper;
        this.targetGroupLBMapProvider = targetGroupLBMapProvider;
        this.awsClientProvider = awsClientProvider;
        this.accountProvider = accountProvider;
    }

    public void run() {
        Set<ResourceRelation> newRouting = new HashSet<>();
        accountProvider.getAccounts().forEach(awsAccount -> awsAccount.getRegions().forEach(region -> {
            EcsClient ecsClient = awsClientProvider.getECSClient(region, awsAccount);
            Map<String, List<String>> serviceARNsByCluster = new HashMap<>();
            Paginator paginator = new Paginator();
            do {
                String api = "EcsClient/listServices";
                ListServicesResponse response = rateLimiter.doWithRateLimit(api, ImmutableSortedMap.of(
                        SCRAPE_REGION_LABEL, region,
                        SCRAPE_ACCOUNT_ID_LABEL, awsAccount.getAccountId(),
                        SCRAPE_OPERATION_LABEL, api
                ), () -> ecsClient.listServices(ListServicesRequest.builder()
                        .nextToken(paginator.getNextToken())
                        .build()));
                if (response.hasServiceArns()) {
                    response.serviceArns().stream()
                            .map(resourceMapper::map)
                            .filter(Optional::isPresent)
                            .map(Optional::get)
                            .forEach(service -> serviceARNsByCluster.computeIfAbsent(service.getChildOf().getName(),
                                    k -> new ArrayList<>()).add(service.getArn()));
                }
                paginator.nextToken(response.nextToken());
            } while (paginator.hasNext());


            serviceARNsByCluster.forEach((cluster, _ARNs) -> {
                try {
                    String api = "EcsClient/describeServices";
                    DescribeServicesResponse response = rateLimiter.doWithRateLimit(api,
                            ImmutableSortedMap.of(
                                    SCRAPE_REGION_LABEL, region,
                                    SCRAPE_ACCOUNT_ID_LABEL, awsAccount.getAccountId(),
                                    SCRAPE_OPERATION_LABEL, api
                            )
                            , () -> ecsClient.describeServices(DescribeServicesRequest.builder()
                                    .cluster(cluster)
                                    .services(_ARNs)
                                    .build()));
                    if (response.hasServices()) {
                        response.services().stream()
                                .filter(service -> resourceMapper.map(service.serviceArn()).isPresent())
                                .forEach(service -> {
                                    Optional<Resource> servResOpt = resourceMapper.map(service.serviceArn());
                                    servResOpt.ifPresent(
                                            servRes -> newRouting.addAll(service.loadBalancers().stream()
                                                    .map(loadBalancer -> resourceMapper.map(
                                                            loadBalancer.targetGroupArn()))
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
            });
        }));
        routing = newRouting;
    }

}
