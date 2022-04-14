/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.exporter;

import ai.asserts.aws.AWSClientProvider;
import ai.asserts.aws.ObjectMapperFactory;
import ai.asserts.aws.RateLimiter;
import ai.asserts.aws.config.ScrapeConfig;
import ai.asserts.aws.ScrapeConfigProvider;
import ai.asserts.aws.model.CWNamespace;
import ai.asserts.aws.resource.Resource;
import ai.asserts.aws.resource.ResourceMapper;
import ai.asserts.aws.resource.ResourceRelation;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableSortedMap;
import lombok.Builder;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.ecs.EcsClient;
import software.amazon.awssdk.services.ecs.model.DescribeTasksRequest;
import software.amazon.awssdk.services.ecs.model.DescribeTasksResponse;
import software.amazon.awssdk.services.ecs.model.ListClustersResponse;
import software.amazon.awssdk.services.ecs.model.ListServicesRequest;
import software.amazon.awssdk.services.ecs.model.ListServicesResponse;
import software.amazon.awssdk.services.ecs.model.ListTasksRequest;
import software.amazon.awssdk.services.ecs.model.ListTasksResponse;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static ai.asserts.aws.MetricNameUtil.SCRAPE_NAMESPACE_LABEL;
import static ai.asserts.aws.MetricNameUtil.SCRAPE_OPERATION_LABEL;
import static ai.asserts.aws.MetricNameUtil.SCRAPE_REGION_LABEL;

/**
 * Exports the Service Discovery file with the list of task instances running in ECS across clusters and services
 * within the clusters
 */
@Slf4j
@Component
public class ECSServiceDiscoveryExporter implements Runnable {
    private final ScrapeConfigProvider scrapeConfigProvider;
    private final AWSClientProvider awsClientProvider;
    private final ResourceMapper resourceMapper;
    private final ECSTaskUtil ecsTaskUtil;
    private final ObjectMapperFactory objectMapperFactory;
    private final RateLimiter rateLimiter;
    private final LBToECSRoutingBuilder lbToECSRoutingBuilder;

    @Getter
    private volatile List<StaticConfig> targets = new ArrayList<>();

    @Getter
    private volatile Set<ResourceRelation> routing = new HashSet<>();

    public ECSServiceDiscoveryExporter(ScrapeConfigProvider scrapeConfigProvider, AWSClientProvider awsClientProvider,
                                       ResourceMapper resourceMapper, ECSTaskUtil ecsTaskUtil,
                                       ObjectMapperFactory objectMapperFactory, RateLimiter rateLimiter,
                                       LBToECSRoutingBuilder lbToECSRoutingBuilder) {
        this.scrapeConfigProvider = scrapeConfigProvider;
        this.awsClientProvider = awsClientProvider;
        this.resourceMapper = resourceMapper;
        this.ecsTaskUtil = ecsTaskUtil;
        this.objectMapperFactory = objectMapperFactory;
        this.rateLimiter = rateLimiter;
        this.lbToECSRoutingBuilder = lbToECSRoutingBuilder;
    }

    @Override
    public void run() {
        Set<ResourceRelation> newRouting = new HashSet<>();

        ScrapeConfig scrapeConfig = scrapeConfigProvider.getScrapeConfig();
        List<StaticConfig> latestTargets = new ArrayList<>();
        Set<String> regions = scrapeConfig.getRegions();
        ImmutableSortedMap<String, String> TELEMETRY_LABELS = ImmutableSortedMap.of(
                SCRAPE_OPERATION_LABEL, "listClusters",
                SCRAPE_NAMESPACE_LABEL, CWNamespace.ecs_svc.getNormalizedNamespace());
        for (String region : regions) {
            SortedMap<String, String> labels = new TreeMap<>(TELEMETRY_LABELS);
            try (EcsClient ecsClient = awsClientProvider.getECSClient(region)) {
                // List clusters just returns the cluster ARN. There is no need to paginate
                ListClustersResponse listClustersResponse = rateLimiter.doWithRateLimit(
                        "EcsClient/listClusters",
                        ImmutableSortedMap.of(
                                SCRAPE_REGION_LABEL, region,
                                SCRAPE_OPERATION_LABEL, "listClusters",
                                SCRAPE_NAMESPACE_LABEL, "AWS/ECS"
                        ),
                        ecsClient::listClusters);
                labels.put(SCRAPE_REGION_LABEL, region);
                if (listClustersResponse.hasClusterArns()) {
                    listClustersResponse.clusterArns()
                            .stream()
                            .map(resourceMapper::map)
                            .filter(Optional::isPresent)
                            .map(Optional::get)
                            .forEach(cluster -> latestTargets.addAll(
                                    buildTargetsInCluster(scrapeConfig, ecsClient, cluster, newRouting)));
                }
            } catch (Exception e) {
                log.error("Failed to get list of ECS Clusters", e);
            }
        }
        routing = newRouting;
        targets = latestTargets;

        if (scrapeConfig.isDiscoverECSTasks()) {
            try {
                File resultFile = new File(scrapeConfig.getEcsTargetSDFile());
                objectMapperFactory.getObjectMapper().writerWithDefaultPrettyPrinter()
                        .writeValue(resultFile, targets);
                log.info("Wrote ECS scrape target SD file {}", resultFile.toURI());
            } catch (IOException e) {
                log.error("Failed to write ECS SD file", e);
            }
        }
    }

    @VisibleForTesting
    List<StaticConfig> buildTargetsInCluster(ScrapeConfig scrapeConfig, EcsClient ecsClient,
                                             Resource cluster, Set<ResourceRelation> newRouting) {
        List<StaticConfig> targets = new ArrayList<>();
        // List services just returns the service ARN. There is no need to paginate
        ListServicesRequest serviceReq = ListServicesRequest.builder()
                .cluster(cluster.getName())
                .build();
        ListServicesResponse serviceResp = rateLimiter.doWithRateLimit("EcsClient/listServices",
                ImmutableSortedMap.of(
                        SCRAPE_REGION_LABEL, cluster.getRegion(),
                        SCRAPE_OPERATION_LABEL, "listServices",
                        SCRAPE_NAMESPACE_LABEL, "AWS/ECS"
                ),
                () -> ecsClient.listServices(serviceReq));
        if (serviceResp.hasServiceArns()) {
            List<Resource> services = new ArrayList<>();
            serviceResp.serviceArns()
                    .stream()
                    .map(resourceMapper::map)
                    .filter(Optional::isPresent)
                    .map(Optional::get)
                    .forEach(service -> {
                        if (scrapeConfig.isDiscoverECSTasks()) {
                            targets.addAll(buildTargetsInService(scrapeConfig, ecsClient, cluster, service));
                        }
                        services.add(service);
                    });
            newRouting.addAll(lbToECSRoutingBuilder.getRoutings(ecsClient, cluster, services));
        }
        return targets;
    }

    @VisibleForTesting
    List<StaticConfig> buildTargetsInService(ScrapeConfig scrapeConfig, EcsClient ecsClient, Resource cluster,
                                             Resource service) {
        List<StaticConfig> scrapeTargets = new ArrayList<>();
        Set<String> taskIds = new TreeSet<>();
        String nextToken = null;
        do {
            ListTasksRequest request = ListTasksRequest.builder()
                    .cluster(cluster.getName())
                    .serviceName(service.getName())
                    .nextToken(nextToken)
                    .build();
            ListTasksResponse tasksResp = rateLimiter.doWithRateLimit("EcsClient/listTasks",
                    ImmutableSortedMap.of(
                            SCRAPE_REGION_LABEL, cluster.getRegion(),
                            SCRAPE_OPERATION_LABEL, "listTasks",
                            SCRAPE_NAMESPACE_LABEL, "AWS/ECS"
                    ),
                    () -> ecsClient.listTasks(request));

            nextToken = tasksResp.nextToken();
            if (tasksResp.hasTaskArns()) {
                for (String taskArn : tasksResp.taskArns()) {
                    taskIds.add(taskArn);
                    if (taskIds.size() == 100) {
                        scrapeTargets.addAll(buildTaskTargets(scrapeConfig, ecsClient, cluster, service, taskIds));
                        taskIds = new TreeSet<>();
                    }
                }
            }
        } while (nextToken != null);

        // Either the first batch was lass than 100 or this is the last batch
        if (taskIds.size() > 0) {
            scrapeTargets.addAll(buildTaskTargets(scrapeConfig, ecsClient, cluster, service, taskIds));
        }
        return scrapeTargets;
    }

    @VisibleForTesting
    List<StaticConfig> buildTaskTargets(ScrapeConfig scrapeConfig, EcsClient ecsClient, Resource cluster,
                                        Resource service, Set<String> taskARNs) {
        List<StaticConfig> configs = new ArrayList<>();
        DescribeTasksRequest request = DescribeTasksRequest.builder()
                .cluster(cluster.getName())
                .tasks(taskARNs)
                .build();
        DescribeTasksResponse taskResponse = rateLimiter.doWithRateLimit("EcsClient/describeTasks",
                ImmutableSortedMap.of(
                        SCRAPE_REGION_LABEL, cluster.getRegion(),
                        SCRAPE_OPERATION_LABEL, "describeTasks",
                        SCRAPE_NAMESPACE_LABEL, "AWS/ECS"
                ),
                () -> ecsClient.describeTasks(request));
        if (taskResponse.hasTasks()) {
            configs.addAll(taskResponse.tasks().stream()
                    .filter(ecsTaskUtil::hasAllInfo)
                    .map(task -> ecsTaskUtil.buildScrapeTarget(scrapeConfig, ecsClient, cluster, service, task))
                    .filter(Optional::isPresent)
                    .map(Optional::get)
                    .collect(Collectors.toList()));
        }
        return configs;
    }

    @Builder
    @Getter
    public static class StaticConfig {
        private final Set<String> targets;
        private final Labels labels;
    }

    @Getter
    @Builder
    public static class Labels {
        @JsonProperty("__metrics_path__")
        private final String metricsPath;
        private final String job;
        @JsonProperty("ecs_cluster")
        private final String cluster;
        @JsonProperty("ecs_taskdef_name")
        private final String taskDefName;
        @JsonProperty("ecs_taskdef_version")
        private final String taskDefVersion;
        @JsonProperty("ecs_task_id")
        private final String taskId;
        @JsonProperty("cw_namespace")
        private final String namespace = "AWS/ECS";
        @JsonProperty("asserts_site")
        private final String region;
    }
}
