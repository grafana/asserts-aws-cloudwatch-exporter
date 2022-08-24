/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.exporter;

import ai.asserts.aws.AWSClientProvider;
import ai.asserts.aws.AccountProvider;
import ai.asserts.aws.AccountProvider.AWSAccount;
import ai.asserts.aws.ObjectMapperFactory;
import ai.asserts.aws.RateLimiter;
import ai.asserts.aws.ScrapeConfigProvider;
import ai.asserts.aws.TagUtil;
import ai.asserts.aws.config.ScrapeConfig;
import ai.asserts.aws.exporter.ECSTaskUtil.SubnetDetails;
import ai.asserts.aws.resource.Resource;
import ai.asserts.aws.resource.ResourceMapper;
import ai.asserts.aws.resource.ResourceRelation;
import ai.asserts.aws.resource.ResourceTagHelper;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import io.prometheus.client.Collector;
import io.prometheus.client.Collector.MetricFamilySamples.Sample;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.util.FileCopyUtils;
import org.springframework.web.client.RestTemplate;
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
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static ai.asserts.aws.MetricNameUtil.SCRAPE_ACCOUNT_ID_LABEL;
import static ai.asserts.aws.MetricNameUtil.SCRAPE_NAMESPACE_LABEL;
import static ai.asserts.aws.MetricNameUtil.SCRAPE_OPERATION_LABEL;
import static ai.asserts.aws.MetricNameUtil.SCRAPE_REGION_LABEL;
import static java.lang.String.format;
import static java.nio.file.Files.newOutputStream;

/**
 * Exports the Service Discovery file with the list of task instances running in ECS across clusters and services
 * within the clusters
 */
@Slf4j
@Component
public class ECSServiceDiscoveryExporter extends Collector implements MetricProvider, InitializingBean {
    public static final String ECS_CONTAINER_METADATA_URI_V4 = "ECS_CONTAINER_METADATA_URI_V4";
    private final RestTemplate restTemplate;
    private final AccountProvider accountProvider;
    private final ScrapeConfigProvider scrapeConfigProvider;
    private final AWSClientProvider awsClientProvider;
    private final ResourceMapper resourceMapper;
    private final ECSTaskUtil ecsTaskUtil;
    private final ObjectMapperFactory objectMapperFactory;
    private final RateLimiter rateLimiter;
    private final LBToECSRoutingBuilder lbToECSRoutingBuilder;
    private final MetricSampleBuilder metricSampleBuilder;

    private final ResourceTagHelper resourceTagHelper;

    private final TagUtil tagUtil;
    private final AtomicReference<SubnetDetails> subnetDetails = new AtomicReference<>(null);

    @Getter
    private volatile List<StaticConfig> targets = new ArrayList<>();

    @Getter
    private volatile Set<ResourceRelation> routing = new HashSet<>();

    private volatile List<Collector.MetricFamilySamples> resourceMetrics = new ArrayList<>();

    public ECSServiceDiscoveryExporter(RestTemplate restTemplate, AccountProvider accountProvider,
                                       ScrapeConfigProvider scrapeConfigProvider,
                                       AWSClientProvider awsClientProvider, ResourceMapper resourceMapper,
                                       ECSTaskUtil ecsTaskUtil, ObjectMapperFactory objectMapperFactory,
                                       RateLimiter rateLimiter, LBToECSRoutingBuilder lbToECSRoutingBuilder,
                                       MetricSampleBuilder metricSampleBuilder,
                                       ResourceTagHelper resourceTagHelper, TagUtil tagUtil) {
        this.restTemplate = restTemplate;
        this.accountProvider = accountProvider;
        this.scrapeConfigProvider = scrapeConfigProvider;
        this.awsClientProvider = awsClientProvider;
        this.resourceMapper = resourceMapper;
        this.ecsTaskUtil = ecsTaskUtil;
        this.objectMapperFactory = objectMapperFactory;
        this.rateLimiter = rateLimiter;
        this.lbToECSRoutingBuilder = lbToECSRoutingBuilder;
        this.metricSampleBuilder = metricSampleBuilder;
        this.resourceTagHelper = resourceTagHelper;
        this.tagUtil = tagUtil;
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        ClassPathResource classPathResource = new ClassPathResource("/dummy-ecs-targets.yml");
        File out = new File(scrapeConfigProvider.getScrapeConfig().getEcsTargetSDFile());
        String src = classPathResource.getURI().toString();
        String dest = out.getAbsolutePath();
        try {
            FileCopyUtils.copy(classPathResource.getInputStream(), newOutputStream(out.toPath()));
            log.info("Copied dummy fd_config {} to {}", src, dest);
        } catch (Exception e) {
            log.error("Failed to copy dummy fd_config {} to {}", src, dest);
        }
    }

    @Override
    public List<MetricFamilySamples> collect() {
        return resourceMetrics;
    }

    @Override
    public void update() {
        Set<ResourceRelation> newRouting = new HashSet<>();
        List<Sample> resourceMetricSamples = new ArrayList<>();

        ScrapeConfig scrapeConfig = scrapeConfigProvider.getScrapeConfig();
        List<StaticConfig> latestTargets = new ArrayList<>();
        discoverSelfSubnet();
        accountProvider.getAccounts().forEach(awsAccount -> awsAccount.getRegions().forEach(region -> {
            ImmutableSortedMap<String, String> TELEMETRY_LABELS =
                    ImmutableSortedMap.of(
                            SCRAPE_ACCOUNT_ID_LABEL, awsAccount.getAccountId(),
                            SCRAPE_REGION_LABEL, region,
                            SCRAPE_OPERATION_LABEL, "listClusters",
                            SCRAPE_NAMESPACE_LABEL, "AWS/ECS");
            SortedMap<String, String> labels = new TreeMap<>(TELEMETRY_LABELS);
            try {
                EcsClient ecsClient = awsClientProvider.getECSClient(region, awsAccount);
                // List clusters just returns the cluster ARN. There is no need to paginate
                ListClustersResponse listClustersResponse = rateLimiter.doWithRateLimit("EcsClient/listClusters",
                        ImmutableSortedMap.of(
                                SCRAPE_ACCOUNT_ID_LABEL, awsAccount.getAccountId(),
                                SCRAPE_REGION_LABEL, region,
                                SCRAPE_OPERATION_LABEL, "listClusters",
                                SCRAPE_NAMESPACE_LABEL, "AWS/ECS"),
                        ecsClient::listClusters);
                labels.put(SCRAPE_REGION_LABEL, region);
                if (listClustersResponse.hasClusterArns()) {
                    listClustersResponse.clusterArns().stream().map(resourceMapper::map).filter(Optional::isPresent)
                            .map(Optional::get).forEach(cluster -> latestTargets.addAll(
                                    buildTargetsInCluster(scrapeConfig, ecsClient, cluster, newRouting,
                                            resourceMetricSamples)));
                }
            } catch (Exception e) {
                log.error("Failed to get list of ECS Clusters", e);
            }
        }));

        routing = newRouting;
        targets = latestTargets.stream()
                .filter(config -> subnetDetails.get() != null && subnetDetails.get().getVpcId()
                        .equals(config.getLabels().getVpcId()))
                .filter(config -> !scrapeConfig.isDiscoverOnlySubnetTasks() || subnetDetails.get().getSubnetId()
                        .equals(config.getLabels().getSubnetId()))
                .filter(config -> scrapeConfig.keepMetric("up", config.getLabels()))
                .collect(Collectors.toList());

        if (resourceMetricSamples.size() > 0) {
            resourceMetrics = Collections.singletonList(metricSampleBuilder.buildFamily(resourceMetricSamples));
        } else {
            resourceMetrics = Collections.emptyList();
        }

        if (scrapeConfig.isDiscoverECSTasks()) {
            try {
                File resultFile = new File(scrapeConfig.getEcsTargetSDFile());
                ObjectWriter objectWriter = objectMapperFactory.getObjectMapper().writerWithDefaultPrettyPrinter();
                objectWriter.writeValue(resultFile, targets);
                if (scrapeConfig.isLogVerbose()) {
                    String targetsFileContent = objectWriter.writeValueAsString(targets);
                    log.info("Wrote ECS scrape target SD file {}\n{}\n", resultFile.toURI(), targetsFileContent);
                } else {
                    log.info("Wrote ECS scrape target SD file {}", resultFile.toURI());
                }
            } catch (IOException e) {
                log.error("Failed to write ECS SD file", e);
            }
        }
    }

    @VisibleForTesting
    List<StaticConfig> buildTargetsInCluster(ScrapeConfig scrapeConfig, EcsClient ecsClient, Resource cluster,
                                             Set<ResourceRelation> newRouting, List<Sample> resourceMetricSamples) {
        List<StaticConfig> targets = new ArrayList<>();
        String nextToken;

        do {
            // List services just returns the service ARN. There is no need to paginate
            ListServicesRequest serviceReq = ListServicesRequest.builder()
                    .cluster(cluster.getName())
                    .build();
            ListServicesResponse serviceResp = rateLimiter.doWithRateLimit("EcsClient/listServices",
                    ImmutableSortedMap.of(
                            SCRAPE_ACCOUNT_ID_LABEL, cluster.getAccount(),
                            SCRAPE_REGION_LABEL, cluster.getRegion(),
                            SCRAPE_OPERATION_LABEL, "listServices",
                            SCRAPE_NAMESPACE_LABEL, "AWS/ECS"),
                    () -> ecsClient.listServices(serviceReq));
            if (serviceResp.hasServiceArns()) {
                // Get tags
                AWSAccount awsAccount = new AWSAccount(cluster.getAccount(), null, null,
                        null, ImmutableSet.of(cluster.getRegion()));
                Map<String, Resource> tagsByName =
                        resourceTagHelper.getResourcesWithTag(awsAccount, cluster.getRegion(), "ecs:service",
                                serviceResp.serviceArns().stream()
                                        .map(resourceMapper::map)
                                        .filter(Optional::isPresent)
                                        .map(opt -> opt.get().getName())
                                        .collect(Collectors.toList()));

                List<Resource> services = new ArrayList<>();
                serviceResp.serviceArns().stream().map(resourceMapper::map).filter(Optional::isPresent)
                        .map(Optional::get)
                        .forEach(service -> {
                            if (scrapeConfig.isDiscoverECSTasks()) {
                                log.info("Discovering ECS Tasks with ECS Scrape Config {}",
                                        scrapeConfig.getECSConfigByNameAndPort());
                                targets.addAll(
                                        buildTargetsInService(scrapeConfig, ecsClient, cluster, service, tagsByName));
                            }
                            services.add(service);
                        });
                newRouting.addAll(lbToECSRoutingBuilder.getRoutings(ecsClient, cluster, services));
            }
            nextToken = serviceResp.nextToken();
        } while (nextToken != null);

        Set<String> capturedTasks = targets.stream().map(StaticConfig::getLabels)
                .map(labels -> format("%s-%s-%s-%s", labels.getAccountId(), labels.getRegion(), labels.getCluster(),
                        labels.getTaskId().substring(labels.getWorkload().length() + 1)))
                .collect(Collectors.toCollection(TreeSet::new));

        // Tasks without service
        do {
            // List services just returns the service ARN. There is no need to paginate
            ListTasksRequest tasksRequest = ListTasksRequest.builder()
                    .cluster(cluster.getName())
                    .build();
            ListTasksResponse tasksResponse = rateLimiter.doWithRateLimit("EcsClient/listTasks",
                    ImmutableSortedMap.of(
                            SCRAPE_ACCOUNT_ID_LABEL, cluster.getAccount(),
                            SCRAPE_REGION_LABEL, cluster.getRegion(),
                            SCRAPE_OPERATION_LABEL, "listTasks",
                            SCRAPE_NAMESPACE_LABEL, "AWS/ECS"),
                    () -> ecsClient.listTasks(tasksRequest));
            if (tasksResponse.hasTaskArns()) {
                Set<String> tasksWithoutService = new TreeSet<>();
                for (String arn : tasksResponse.taskArns()) {
                    resourceMapper.map(arn).ifPresent(resource -> {
                        String taskKey = format("%s-%s-%s-%s", resource.getAccount(), resource.getRegion(),
                                resource.getChildOf().getName(), resource.getName());
                        if (!capturedTasks.contains(taskKey)) {
                            tasksWithoutService.add(arn);
                        }
                    });
                }
                if (tasksWithoutService.size() > 0) {
                    targets.addAll(buildTaskTargets(scrapeConfig, ecsClient, cluster, Optional.empty(),
                            new TreeSet<>(tasksWithoutService), Collections.emptyMap()));
                }
            }
            nextToken = tasksResponse.nextToken();
        } while (nextToken != null);

        return targets;
    }

    @VisibleForTesting
    List<StaticConfig> buildTargetsInService(ScrapeConfig scrapeConfig, EcsClient ecsClient, Resource cluster,
                                             Resource service, Map<String, Resource> resourceWithTags) {
        List<StaticConfig> scrapeTargets = new ArrayList<>();
        Set<String> taskARNs = new TreeSet<>();
        String nextToken = null;
        Map<String, String> tagLabels = new TreeMap<>();
        if (resourceWithTags.containsKey(service.getName())) {
            tagLabels.putAll(tagUtil.tagLabels(resourceWithTags.get(service.getName()).getTags()));
        }
        do {
            ListTasksRequest request = ListTasksRequest.builder()
                    .cluster(cluster.getName())
                    .serviceName(service.getName())
                    .nextToken(nextToken).build();
            ListTasksResponse tasksResp = rateLimiter.doWithRateLimit("EcsClient/listTasks",
                    ImmutableSortedMap.of(SCRAPE_ACCOUNT_ID_LABEL, cluster.getAccount(), SCRAPE_REGION_LABEL,
                            cluster.getRegion(), SCRAPE_OPERATION_LABEL, "listTasks", SCRAPE_NAMESPACE_LABEL,
                            "AWS/ECS"), () -> ecsClient.listTasks(request));

            nextToken = tasksResp.nextToken();
            if (tasksResp.hasTaskArns()) {
                for (String taskArn : tasksResp.taskArns()) {
                    taskARNs.add(taskArn);
                    if (taskARNs.size() == 100) {
                        scrapeTargets.addAll(buildTaskTargets(scrapeConfig, ecsClient, cluster, Optional.of(service),
                                taskARNs, tagLabels));
                        taskARNs = new TreeSet<>();
                    }
                }
            }
        } while (nextToken != null);

        // Either the first batch was less than 100 or this is the last batch
        if (taskARNs.size() > 0) {
            scrapeTargets.addAll(buildTaskTargets(scrapeConfig, ecsClient, cluster, Optional.of(service), taskARNs,
                    tagLabels));
        }
        return scrapeTargets;
    }

    @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
    @VisibleForTesting
    List<StaticConfig> buildTaskTargets(ScrapeConfig scrapeConfig, EcsClient ecsClient, Resource cluster,
                                        Optional<Resource> service, Set<String> taskARNs,
                                        Map<String, String> tagLabels) {
        List<StaticConfig> configs = new ArrayList<>();
        DescribeTasksRequest request =
                DescribeTasksRequest.builder().cluster(cluster.getName()).tasks(taskARNs).build();
        DescribeTasksResponse taskResponse = rateLimiter.doWithRateLimit("EcsClient/describeTasks",
                ImmutableSortedMap.of(SCRAPE_ACCOUNT_ID_LABEL, cluster.getAccount(), SCRAPE_REGION_LABEL,
                        cluster.getRegion(), SCRAPE_OPERATION_LABEL, "describeTasks", SCRAPE_NAMESPACE_LABEL,
                        "AWS/ECS"), () -> ecsClient.describeTasks(request));
        if (taskResponse.hasTasks()) {
            configs.addAll(taskResponse.tasks().stream()
                    .filter(ecsTaskUtil::hasAllInfo)
                    .flatMap(task ->
                            ecsTaskUtil.buildScrapeTargets(scrapeConfig, ecsClient, cluster, service,
                                    task, tagLabels).stream())
                    .collect(Collectors.toList()));
        }
        return configs;
    }


    @VisibleForTesting
    String getMetaDataURI() {
        return System.getenv(ECS_CONTAINER_METADATA_URI_V4);
    }

    private void discoverSelfSubnet() {
        if (this.subnetDetails.get() == null) {
            String containerMetaURI = getMetaDataURI();
            log.info("Container stats scrape task got URI {}", containerMetaURI);
            if (containerMetaURI != null) {
                try {
                    String taskMetaDataURL = containerMetaURI + "/task";
                    URI uri = URI.create(taskMetaDataURL);
                    TaskMetaData taskMetaData = restTemplate.getForObject(uri, TaskMetaData.class);
                    if (taskMetaData != null) {
                        resourceMapper.map(taskMetaData.getTaskARN()).ifPresent(taskResource -> {
                            subnetDetails.set(ecsTaskUtil.getSubnetDetails(taskResource));
                            log.info("Discovered self subnet as {}", subnetDetails);
                        });
                    }
                } catch (Exception e) {
                    log.error("Failed to discover self task details", e);
                }
            } else {
                log.warn("Env variables ['ECS_CONTAINER_METADATA_URI_V4','ECS_CONTAINER_METADATA_URI'] not found");
            }
        }
    }

    @Builder
    @Getter
    public static class StaticConfig {
        private final Set<String> targets = new TreeSet<>();
        private final Labels labels;
    }

    @Getter
    @JsonIgnoreProperties(ignoreUnknown = true)
    @EqualsAndHashCode
    @ToString
    @SuperBuilder
    @NoArgsConstructor
    public static class TaskMetaData {
        @JsonProperty("TaskARN")
        private String taskARN;
    }
}
