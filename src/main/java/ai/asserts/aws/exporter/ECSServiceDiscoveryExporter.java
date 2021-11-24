/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.exporter;

import ai.asserts.aws.AWSClientProvider;
import ai.asserts.aws.ObjectMapperFactory;
import ai.asserts.aws.cloudwatch.config.ScrapeConfig;
import ai.asserts.aws.cloudwatch.config.ScrapeConfigProvider;
import ai.asserts.aws.cloudwatch.model.CWNamespace;
import ai.asserts.aws.resource.Resource;
import ai.asserts.aws.resource.ResourceMapper;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableSortedMap;
import lombok.AllArgsConstructor;
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
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static ai.asserts.aws.MetricNameUtil.SCRAPE_ERROR_COUNT_METRIC;
import static ai.asserts.aws.MetricNameUtil.SCRAPE_LATENCY_METRIC;
import static ai.asserts.aws.MetricNameUtil.SCRAPE_NAMESPACE_LABEL;
import static ai.asserts.aws.MetricNameUtil.SCRAPE_OPERATION_LABEL;

/**
 * Exports the Service Discovery file with the list of task instances running in ECS across clusters and services
 * within the clusters
 */
@AllArgsConstructor
@Slf4j
@Component
public class ECSServiceDiscoveryExporter implements Runnable {
    private final ScrapeConfigProvider scrapeConfigProvider;
    private final AWSClientProvider awsClientProvider;
    private final ResourceMapper resourceMapper;
    private final ECSTaskUtil ecsTaskUtil;
    private final BasicMetricCollector metricCollector;
    private final ObjectMapperFactory objectMapperFactory;

    @Override
    public void run() {
        ScrapeConfig scrapeConfig = scrapeConfigProvider.getScrapeConfig();
        if (scrapeConfig.isECSMonitoringOn()) {
            List<StaticConfig> latestTargets = new ArrayList<>();
            Set<String> regions = scrapeConfig.getRegions();
            ImmutableSortedMap<String, String> TELEMETRY_LABELS = ImmutableSortedMap.of(
                    SCRAPE_OPERATION_LABEL, "listClusters",
                    SCRAPE_NAMESPACE_LABEL, CWNamespace.ecs_svc.getNormalizedNamespace());
            for (String region : regions) {
                try (EcsClient ecsClient = awsClientProvider.getECSClient(region)) {
                    // List clusters just returns the cluster ARN. There is no need to paginate
                    long tick = System.currentTimeMillis();
                    ListClustersResponse listClustersResponse = ecsClient.listClusters();
                    tick = System.currentTimeMillis() - tick;
                    metricCollector.recordLatency(SCRAPE_LATENCY_METRIC, TELEMETRY_LABELS, tick);
                    if (listClustersResponse.hasClusterArns()) {
                        listClustersResponse.clusterArns()
                                .stream()
                                .map(resourceMapper::map)
                                .filter(Optional::isPresent)
                                .map(Optional::get)
                                .forEach(cluster ->
                                        latestTargets.addAll(buildTargetsInCluster(scrapeConfig, ecsClient, cluster)));
                    }
                } catch (Exception e) {
                    metricCollector.recordCounterValue(SCRAPE_ERROR_COUNT_METRIC, TELEMETRY_LABELS, 1);
                    log.error("Failed to get list of ECS Clusters", e);
                }
            }
            try {
                objectMapperFactory.getObjectMapper().writerWithDefaultPrettyPrinter()
                        .writeValue(new File("ecs_task_scrape_targets.yml"), latestTargets);
            } catch (IOException e) {
                metricCollector.recordCounterValue(SCRAPE_ERROR_COUNT_METRIC, ImmutableSortedMap.of(
                        SCRAPE_OPERATION_LABEL, "ecs_sd_file_generation"), 1);
                log.error("Failed to get list of ECS Clusters", e);
            }
        }
    }

    @VisibleForTesting
    List<StaticConfig> buildTargetsInCluster(ScrapeConfig scrapeConfig, EcsClient ecsClient, Resource cluster) {
        List<StaticConfig> targets = new ArrayList<>();
        // List services just returns the service ARN. There is no need to paginate
        ListServicesRequest serviceReq = ListServicesRequest.builder()
                .cluster(cluster.getName())
                .build();
        ListServicesResponse serviceResp = ecsClient.listServices(serviceReq);
        if (serviceResp.hasServiceArns()) {
            serviceResp.serviceArns()
                    .stream()
                    .map(resourceMapper::map)
                    .filter(Optional::isPresent)
                    .map(Optional::get)
                    .forEach(service ->
                            targets.addAll(buildTargetsInService(scrapeConfig, ecsClient, cluster, service)));
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
            long time = System.currentTimeMillis();
            ListTasksResponse tasksResp = ecsClient.listTasks(ListTasksRequest.builder()
                    .cluster(cluster.getName())
                    .serviceName(service.getName())
                    .nextToken(nextToken)
                    .build());
            time = System.currentTimeMillis() - time;
            metricCollector.recordLatency(SCRAPE_LATENCY_METRIC, ImmutableSortedMap.of(), time);
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
        DescribeTasksResponse taskResponse = ecsClient.describeTasks(DescribeTasksRequest.builder()
                .cluster(cluster.getName())
                .tasks(taskARNs)
                .build());
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
        private final String namespace = "AWS/ECS";
    }
}
