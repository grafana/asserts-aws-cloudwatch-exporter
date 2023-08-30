/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.exporter;

import ai.asserts.aws.AWSClientProvider;
import ai.asserts.aws.AWSApiCallRateLimiter;
import ai.asserts.aws.ScrapeConfigProvider;
import ai.asserts.aws.SimpleTenantTask;
import ai.asserts.aws.SnakeCaseUtil;
import ai.asserts.aws.TaskExecutorUtil;
import ai.asserts.aws.account.AWSAccount;
import ai.asserts.aws.account.AccountProvider;
import ai.asserts.aws.config.ScrapeConfig;
import ai.asserts.aws.exporter.ECSServiceDiscoveryExporter.StaticConfig;
import ai.asserts.aws.resource.Resource;
import ai.asserts.aws.resource.ResourceMapper;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Lists;
import io.prometheus.client.Collector;
import io.prometheus.client.Collector.MetricFamilySamples.Sample;
import io.prometheus.client.CollectorRegistry;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.ecs.EcsClient;
import software.amazon.awssdk.services.ecs.model.DescribeTasksRequest;
import software.amazon.awssdk.services.ecs.model.DescribeTasksResponse;
import software.amazon.awssdk.services.ecs.model.DesiredStatus;
import software.amazon.awssdk.services.ecs.model.ListTasksRequest;
import software.amazon.awssdk.services.ecs.model.ListTasksResponse;
import software.amazon.awssdk.services.ecs.model.Task;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import static ai.asserts.aws.MetricNameUtil.SCRAPE_ACCOUNT_ID_LABEL;
import static ai.asserts.aws.MetricNameUtil.SCRAPE_NAMESPACE_LABEL;
import static ai.asserts.aws.MetricNameUtil.SCRAPE_OPERATION_LABEL;
import static ai.asserts.aws.MetricNameUtil.SCRAPE_REGION_LABEL;
import static ai.asserts.aws.MetricNameUtil.TENANT;

/**
 * Builds ECS Task scrape targets. Scraping ECS is best done using the ECS Sidecar.
 */
@Component
@Slf4j
public class ECSTaskProvider extends Collector implements Runnable, InitializingBean {
    public static final String TASK_META_METRIC = "aws_ecs_task_info";
    public static final String CONTAINER_LOG_INFO_METRIC = "aws_ecs_container_log_info";
    private final AWSClientProvider awsClientProvider;
    private final ScrapeConfigProvider scrapeConfigProvider;
    private final AccountProvider accountProvider;
    private final AWSApiCallRateLimiter rateLimiter;
    private final ResourceMapper resourceMapper;
    private final ECSClusterProvider ecsClusterProvider;
    private final ECSTaskUtil ecsTaskUtil;
    private final MetricSampleBuilder sampleBuilder;
    private final TaskExecutorUtil taskExecutorUtil;
    private final CollectorRegistry collectorRegistry;
    private final SnakeCaseUtil snakeCaseUtil;
    private final int describeTasksBatchSize;

    @Getter
    @VisibleForTesting
    private final Map<Resource, Map<Resource, List<StaticConfig>>> tasksByCluster = new ConcurrentHashMap<>();

    public ECSTaskProvider(AWSClientProvider awsClientProvider, ScrapeConfigProvider scrapeConfigProvider,
                           AccountProvider accountProvider, AWSApiCallRateLimiter rateLimiter, ResourceMapper resourceMapper,
                           ECSClusterProvider ecsClusterProvider, ECSTaskUtil ecsTaskUtil,
                           MetricSampleBuilder sampleBuilder, CollectorRegistry collectorRegistry,
                           TaskExecutorUtil taskExecutorUtil, SnakeCaseUtil snakeCaseUtil,
                           @Value("${aws_exporter.ecs_describeTasks_batch_size:100}") int describeTaskBatchSize) {
        this.awsClientProvider = awsClientProvider;
        this.scrapeConfigProvider = scrapeConfigProvider;
        this.accountProvider = accountProvider;
        this.rateLimiter = rateLimiter;
        this.resourceMapper = resourceMapper;
        this.ecsClusterProvider = ecsClusterProvider;
        this.ecsTaskUtil = ecsTaskUtil;
        this.sampleBuilder = sampleBuilder;
        this.collectorRegistry = collectorRegistry;
        this.taskExecutorUtil = taskExecutorUtil;
        this.snakeCaseUtil = snakeCaseUtil;
        this.describeTasksBatchSize = describeTaskBatchSize;
    }

    @Override
    public List<MetricFamilySamples> collect() {
        List<Sample> metaMetricSamples = new ArrayList<>();
        List<Sample> logInfoSamples = new ArrayList<>();
        tasksByCluster.values().stream()
                .flatMap(taskMap -> taskMap.values().stream())
                .flatMap(Collection::stream)
                .forEach(target -> {
                    Labels labels = target.getLabels();
                    sampleBuilder.buildSingleSample(TASK_META_METRIC, labels, 1.0D)
                            .ifPresent(metaMetricSamples::add);

                    target.getLogConfigs().forEach(logConfig -> {
                        Map<String, String> logInfoLabels = new TreeMap<>();
                        logInfoLabels.put(TENANT, labels.getTenant());
                        logInfoLabels.put(SCRAPE_ACCOUNT_ID_LABEL, labels.getAccountId());
                        logInfoLabels.put(SCRAPE_REGION_LABEL, labels.getRegion());
                        logInfoLabels.put("cluster", labels.getCluster());
                        logInfoLabels.put("workload", labels.getWorkload());
                        logInfoLabels.put("container", labels.getContainer());
                        logInfoLabels.put("driver_name", logConfig.getLogDriver());
                        logConfig.getOptions()
                                .forEach((key, value) -> logInfoLabels.put(snakeCaseUtil.toSnakeCase(key), value));
                        sampleBuilder.buildSingleSample(CONTAINER_LOG_INFO_METRIC, logInfoLabels, 1.0D)
                                .ifPresent(logInfoSamples::add);
                    });
                });
        List<MetricFamilySamples> familySamples = new ArrayList<>();
        sampleBuilder.buildFamily(metaMetricSamples).ifPresent(familySamples::add);
        sampleBuilder.buildFamily(logInfoSamples).ifPresent(familySamples::add);
        return familySamples;
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        collectorRegistry.register(this);
    }

    public List<StaticConfig> getScrapeTargets() {
        return tasksByCluster.values().stream()
                .flatMap(taskMap -> taskMap.values().stream())
                .flatMap(Collection::stream)
                .collect(Collectors.toList());
    }

    @Override
    public void run() {
        // Scrape target building works only when the exporter is installed in each account
        for (AWSAccount account : accountProvider.getAccounts()) {
            ScrapeConfig scrapeConfig = scrapeConfigProvider.getScrapeConfig(account.getTenant());
            for (String region : account.getRegions()) {
                taskExecutorUtil.executeAccountTask(account, new SimpleTenantTask<Void>() {
                    @Override
                    public Void call() {
                        Map<Resource, List<Resource>> clusterWiseNewTasks = new HashMap<>();
                        EcsClient ecsClient = awsClientProvider.getECSClient(region, account);
                        for (Resource cluster : ecsClusterProvider.getClusters(account, region)) {
                            discoverNewTasks(clusterWiseNewTasks, ecsClient, cluster);
                        }
                        buildNewTargets(account, scrapeConfig, clusterWiseNewTasks, ecsClient);
                        return null;
                    }
                });
            }
        }
    }

    @VisibleForTesting
    void discoverNewTasks(Map<Resource, List<Resource>> clusterWiseNewTasks, EcsClient ecsClient, Resource cluster) {
        Map<Resource, List<StaticConfig>> current =
                tasksByCluster.computeIfAbsent(cluster, k -> new HashMap<>());
        Paginator taskPaginator = new Paginator();
        Set<Resource> latestTasks = new LinkedHashSet<>();
        do {
            ListTasksRequest tasksRequest = ListTasksRequest.builder()
                    .cluster(cluster.getName())
                    .desiredStatus(DesiredStatus.RUNNING)
                    .nextToken(taskPaginator.getNextToken())
                    .build();
            String operationName = "EcsClient/listTasks";
            ListTasksResponse tasksResponse = rateLimiter.doWithRateLimit(operationName,
                    ImmutableSortedMap.of(
                            SCRAPE_ACCOUNT_ID_LABEL, cluster.getAccount(),
                            SCRAPE_REGION_LABEL, cluster.getRegion(),
                            SCRAPE_OPERATION_LABEL, operationName,
                            SCRAPE_NAMESPACE_LABEL, "AWS/ECS"),
                    () -> ecsClient.listTasks(tasksRequest));

            // Build list of new tasks for which we need to make the describeTasks call
            if (tasksResponse.hasTaskArns()) {
                log.info("Found {} tasks in cluster {}", tasksResponse.taskArns().size(), cluster.getArn());
                // Build the current task list
                latestTasks.addAll(tasksResponse.taskArns().stream()
                        .map(resourceMapper::map)
                        .filter(Optional::isPresent)
                        .map(Optional::get)
                        .collect(Collectors.toSet()));
            }
            taskPaginator.nextToken(tasksResponse.nextToken());
        } while (taskPaginator.hasNext());

        // Remove tasks that are not present
        // Retain only new tasks
        current.entrySet().removeIf(entry -> !latestTasks.contains(entry.getKey()));
        latestTasks.removeIf(current::containsKey);
        if (latestTasks.size() > 0) {
            log.info("Found {} new tasks in cluster {}", latestTasks.size(), cluster.getArn());
        }
        clusterWiseNewTasks.computeIfAbsent(cluster, k -> new ArrayList<>()).addAll(latestTasks);
    }

    @VisibleForTesting
    void buildNewTargets(AWSAccount account, ScrapeConfig scrapeConfig,
                         Map<Resource, List<Resource>> clusterWiseNewTasks,
                         EcsClient ecsClient) {
        // Make the describeTasks call for the new tasks and build the scrape targets
        clusterWiseNewTasks.forEach((cluster, tasks) -> {
            if (!tasks.isEmpty()) {
                // Batch in sizes of 100
                String operationName = "EcsClient/describeTasks";

                List<Resource> allTasks = new ArrayList<>(tasks);
                List<List<Resource>> batches = Lists.partition(allTasks, describeTasksBatchSize);
                for(List<Resource> batch : batches) {
                    DescribeTasksRequest request =
                            DescribeTasksRequest.builder()
                                    .cluster(cluster.getName())
                                    .tasks(batch.stream().map(Resource::getArn).collect(Collectors.toList()))
                                    .build();
                    DescribeTasksResponse taskResponse = rateLimiter.doWithRateLimit(operationName,
                            ImmutableSortedMap.of(
                                    SCRAPE_ACCOUNT_ID_LABEL, cluster.getAccount(),
                                    SCRAPE_REGION_LABEL, cluster.getRegion(),
                                    SCRAPE_OPERATION_LABEL, operationName,
                                    SCRAPE_NAMESPACE_LABEL, "AWS/ECS"),
                            () -> ecsClient.describeTasks(request));
                    if (taskResponse.hasTasks()) {
                        taskResponse.tasks().stream()
                                .filter(ecsTaskUtil::hasAllInfo)
                                .forEach(task -> resourceMapper.map(task.taskArn()).ifPresent(taskResource -> {
                                    String tenantName = account.getTenant();
                                    List<StaticConfig> staticConfigs =
                                            ecsTaskUtil.buildScrapeTargets(
                                                    account,
                                                    scrapeConfigProvider.getScrapeConfig(tenantName),
                                                    ecsClient,
                                                    cluster,
                                                    getService(task), task);
                                    Map<Resource, List<StaticConfig>> clusterTargets =
                                            tasksByCluster.computeIfAbsent(cluster, k -> new HashMap<>());
                                    clusterTargets.put(taskResource, staticConfigs);
                                }));
                    }
                }
            }
        });
    }


    Optional<String> getService(Task task) {
        return task.group() != null && task.group().contains("service:") ?
                Optional.of(task.group().split(":")[1]) : Optional.empty();
    }

}
