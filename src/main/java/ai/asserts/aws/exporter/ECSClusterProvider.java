/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.exporter;

import ai.asserts.aws.AWSClientProvider;
import ai.asserts.aws.account.AWSAccount;
import ai.asserts.aws.AWSApiCallRateLimiter;
import ai.asserts.aws.resource.Resource;
import ai.asserts.aws.resource.ResourceMapper;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableSortedMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.ecs.EcsClient;
import software.amazon.awssdk.services.ecs.model.DescribeClustersRequest;
import software.amazon.awssdk.services.ecs.model.DescribeClustersResponse;
import software.amazon.awssdk.services.ecs.model.ListClustersResponse;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static ai.asserts.aws.MetricNameUtil.SCRAPE_ACCOUNT_ID_LABEL;
import static ai.asserts.aws.MetricNameUtil.SCRAPE_NAMESPACE_LABEL;
import static ai.asserts.aws.MetricNameUtil.SCRAPE_OPERATION_LABEL;
import static ai.asserts.aws.MetricNameUtil.SCRAPE_REGION_LABEL;

@Component
@Slf4j
public class ECSClusterProvider {
    private final AWSClientProvider awsClientProvider;
    private final AWSApiCallRateLimiter rateLimiter;
    private final ResourceMapper resourceMapper;
    private final Map<String, Map<String, Supplier<Set<Resource>>>> clusterProviders = new ConcurrentHashMap<>();

    public ECSClusterProvider(AWSClientProvider awsClientProvider, AWSApiCallRateLimiter rateLimiter,
                              ResourceMapper resourceMapper) {
        this.awsClientProvider = awsClientProvider;
        this.rateLimiter = rateLimiter;
        this.resourceMapper = resourceMapper;
    }

    public Set<Resource> getClusters(AWSAccount account, String region) {
        return clusterProviders
                .computeIfAbsent(account.getAccountId(), k -> new ConcurrentHashMap<>())
                .computeIfAbsent(region,
                        k -> Suppliers.memoizeWithExpiration(() ->
                                getClustersByRegion(account, k), 15, TimeUnit.MINUTES))
                .get();
    }

    private Set<Resource> getClustersByRegion(AWSAccount awsAccount, String region) {
        Set<Resource> clusters = new LinkedHashSet<>();
        String operationName = "EcsClient/listClusters";
        ImmutableSortedMap<String, String> TELEMETRY_LABELS =
                ImmutableSortedMap.of(
                        SCRAPE_ACCOUNT_ID_LABEL, awsAccount.getAccountId(),
                        SCRAPE_REGION_LABEL, region,
                        SCRAPE_OPERATION_LABEL, operationName,
                        SCRAPE_NAMESPACE_LABEL, "AWS/ECS");
        SortedMap<String, String> labels = new TreeMap<>(TELEMETRY_LABELS);

        try {
            Set<String> allClusterARNs = new TreeSet<>();
            EcsClient ecsClient = awsClientProvider.getECSClient(region, awsAccount);
            Paginator paginator = new Paginator();
            do {
                ListClustersResponse listClustersResponse = rateLimiter.doWithRateLimit(operationName,
                        labels,
                        ecsClient::listClusters);
                if (listClustersResponse.hasClusterArns()) {
                    allClusterARNs.addAll(listClustersResponse.clusterArns());
                }
                paginator.nextToken(listClustersResponse.nextToken());
            } while (paginator.hasNext());

            log.info("Found {} total clusters : {}", allClusterARNs.size(), allClusterARNs);

            // Filter by ACTIVE clusters
            operationName = "EcsClient/DescribeClusters";
            labels.put(SCRAPE_OPERATION_LABEL, operationName);
            DescribeClustersRequest describeClustersRequest = DescribeClustersRequest.builder()
                    .clusters(allClusterARNs)
                    .build();
            DescribeClustersResponse describeClustersResponse = rateLimiter.doWithRateLimit(operationName,
                    labels,
                    () -> ecsClient.describeClusters(describeClustersRequest));
            if (describeClustersResponse.hasClusters()) {
                clusters.addAll(describeClustersResponse.clusters().stream()
                        .filter(cluster -> "ACTIVE".equals(cluster.status()))
                        .map(cluster -> resourceMapper.map(cluster.clusterArn()))
                        .filter(Optional::isPresent)
                        .map(Optional::get)
                        .collect(Collectors.toSet()));
            }
        } catch (Exception e) {
            log.error("Failed to get list of ECS Clusters", e);
        }

        log.info("Found {} ACTIVE clusters : {}", clusters.size(), clusters);
        return clusters;
    }
}
