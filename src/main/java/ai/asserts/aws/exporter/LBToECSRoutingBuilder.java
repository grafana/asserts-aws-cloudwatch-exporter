/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.exporter;

import ai.asserts.aws.AWSClientProvider;
import ai.asserts.aws.AWSApiCallRateLimiter;
import ai.asserts.aws.SimpleTenantTask;
import ai.asserts.aws.TaskExecutorUtil;
import ai.asserts.aws.account.AWSAccount;
import ai.asserts.aws.account.AccountProvider;
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
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

import static ai.asserts.aws.MetricNameUtil.SCRAPE_ACCOUNT_ID_LABEL;
import static ai.asserts.aws.MetricNameUtil.SCRAPE_OPERATION_LABEL;
import static ai.asserts.aws.MetricNameUtil.SCRAPE_REGION_LABEL;

@Component
@Slf4j
public class LBToECSRoutingBuilder implements Runnable {
    private final AWSApiCallRateLimiter rateLimiter;
    private final ResourceMapper resourceMapper;
    private final TargetGroupLBMapProvider targetGroupLBMapProvider;
    private final AWSClientProvider awsClientProvider;
    private final AccountProvider accountProvider;
    private final ECSClusterProvider ecsClusterProvider;
    private final TaskExecutorUtil taskExecutorUtil;

    @Getter
    private volatile Set<ResourceRelation> routing = new HashSet<>();

    public LBToECSRoutingBuilder(AWSApiCallRateLimiter rateLimiter, ResourceMapper resourceMapper,
                                 TargetGroupLBMapProvider targetGroupLBMapProvider,
                                 AWSClientProvider awsClientProvider, AccountProvider accountProvider,
                                 ECSClusterProvider ecsClusterProvider, TaskExecutorUtil taskExecutorUtil) {
        this.rateLimiter = rateLimiter;
        this.resourceMapper = resourceMapper;
        this.targetGroupLBMapProvider = targetGroupLBMapProvider;
        this.awsClientProvider = awsClientProvider;
        this.accountProvider = accountProvider;
        this.ecsClusterProvider = ecsClusterProvider;
        this.taskExecutorUtil = taskExecutorUtil;
    }

    public void run() {
        Set<ResourceRelation> newRouting = new HashSet<>();
        List<Future<Set<ResourceRelation>>> futures = new ArrayList<>();
        accountProvider.getAccounts().forEach(awsAccount -> awsAccount.getRegions().forEach(region ->
                futures.add(taskExecutorUtil.executeAccountTask(awsAccount,
                        new SimpleTenantTask<Set<ResourceRelation>>() {
                            @Override
                            public Set<ResourceRelation> call() {
                                return buildRelationships(region, awsAccount);
                            }
                        }))));
        taskExecutorUtil.awaitAll(futures, newRouting::addAll);
        routing = newRouting;
    }

    private Set<ResourceRelation> buildRelationships(String region, AWSAccount awsAccount) {
        Set<ResourceRelation> newRouting = new HashSet<>();
        EcsClient ecsClient = awsClientProvider.getECSClient(region, awsAccount);
        Set<Resource> clusters = ecsClusterProvider.getClusters(awsAccount, region);
        clusters.forEach(cluster -> {
            Set<String> serviceARNs = new HashSet<>();
            Paginator paginator = new Paginator();
            do {
                String api = "EcsClient/listServices";
                ListServicesResponse response = rateLimiter.doWithRateLimit(api, ImmutableSortedMap.of(
                        SCRAPE_REGION_LABEL, region,
                        SCRAPE_ACCOUNT_ID_LABEL, awsAccount.getAccountId(),
                        SCRAPE_OPERATION_LABEL, api
                ), () -> ecsClient.listServices(ListServicesRequest.builder()
                        .cluster(cluster.getName())
                        .nextToken(paginator.getNextToken())
                        .build()));
                if (response.hasServiceArns()) {
                    serviceARNs.addAll(response.serviceArns());
                }
                paginator.nextToken(response.nextToken());
            } while (paginator.hasNext());

            if (!serviceARNs.isEmpty()) {
                discoverRelationships(newRouting, awsAccount, region, ecsClient, cluster, serviceARNs);
            }
        });
        return newRouting;
    }

    private void discoverRelationships(Set<ResourceRelation> newRouting, AWSAccount awsAccount,
                                       String region, EcsClient ecsClient, Resource cluster, Set<String> serviceARNs) {
        SortedSet<String> orderedARNs = new TreeSet<>(serviceARNs);
        while (orderedARNs.size() > 0) {
            SortedSet<String> nextBatch = new TreeSet<>();
            for (String nextARN : orderedARNs) {
                if (nextBatch.size() == 10) {
                    break;
                } else {
                    nextBatch.add(nextARN);
                }
            }
            orderedARNs.removeAll(nextBatch);
            try {
                String api = "EcsClient/describeServices";
                DescribeServicesResponse response = rateLimiter.doWithRateLimit(api,
                        ImmutableSortedMap.of(
                                SCRAPE_REGION_LABEL, region,
                                SCRAPE_ACCOUNT_ID_LABEL, awsAccount.getAccountId(),
                                SCRAPE_OPERATION_LABEL, api
                        )
                        , () -> ecsClient.describeServices(DescribeServicesRequest.builder()
                                .cluster(cluster.getName())
                                .services(nextBatch)
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
        }
    }
}
