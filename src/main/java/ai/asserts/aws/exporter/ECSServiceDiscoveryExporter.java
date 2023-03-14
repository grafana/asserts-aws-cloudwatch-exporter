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
import ai.asserts.aws.config.ScrapeConfig.SubnetDetails;
import ai.asserts.aws.resource.Resource;
import ai.asserts.aws.resource.ResourceMapper;
import ai.asserts.aws.resource.ResourceRelation;
import ai.asserts.aws.resource.ResourceTagHelper;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.google.common.annotations.VisibleForTesting;
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
import java.util.Arrays;
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
import static org.springframework.util.StringUtils.hasLength;

/**
 * Exports the Service Discovery file with the list of task instances running in ECS across clusters and services
 * within the clusters
 */
@Slf4j
@Component
public class ECSServiceDiscoveryExporter extends Collector implements MetricProvider, InitializingBean {
    public static final String ECS_CONTAINER_METADATA_URI_V4 = "ECS_CONTAINER_METADATA_URI_V4";
    public static final String SCRAPE_ECS_SUBNETS = "SCRAPE_ECS_SUBNETS";
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
    @Getter
    private final AtomicReference<SubnetDetails> subnetDetails = new AtomicReference<>(null);

    @Getter
    protected final Set<String> subnetsToScrape = new TreeSet<>();

    @Getter
    private volatile List<StaticConfig> targets = new ArrayList<>();

    @Getter
    private volatile Set<ResourceRelation> routing = new HashSet<>();

    @Getter
    private volatile List<MetricFamilySamples> taskMetaMetric = new ArrayList<>();


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

        identifySubnetsToScrape();
    }

    @VisibleForTesting
    void identifySubnetsToScrape() {
        if (System.getenv(SCRAPE_ECS_SUBNETS) != null) {
            this.subnetsToScrape.addAll(
                    Arrays.stream(System.getenv(SCRAPE_ECS_SUBNETS).split(","))
                            .map(String::trim)
                            .collect(Collectors.toSet()));
        }
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
        discoverSelfSubnet();
    }

    /**
     * If the exporter is installed in multiple VPCs and multiple subnets in an AWS Account, only one of the exporters
     * will export the cloudwatch metrics and AWS Config metadata. The exporter doesn't automatically determine which
     * instance is primary. It has to be specified in the configuration by specifying either the VPC or the subnet or
     * both.
     *
     * @return <code>true</code> if this exporter should function as a primary exporter in this account.
     * <code>false</code> otherwise.
     */
    public boolean isPrimaryExporter() {
        ScrapeConfig scrapeConfig = scrapeConfigProvider.getScrapeConfig();
        Map<String, SubnetDetails> primaryExportersByAccount = scrapeConfig.getPrimaryExporterByAccount();
        SubnetDetails primaryConfig = primaryExportersByAccount.get(accountProvider.getCurrentAccountId());
        return primaryConfig == null ||
                (!hasLength(primaryConfig.getVpcId()) || runningInVPC(primaryConfig.getVpcId())) &&
                        (!hasLength(primaryConfig.getSubnetId()) || runningInSubnet(primaryConfig.getSubnetId()));
    }

    public boolean runningInVPC(String vpcId) {
        if (subnetDetails.get() != null) {
            return vpcId.equals(subnetDetails.get().getVpcId());
        }
        return false;
    }

    public boolean runningInSubnet(String subnetId) {
        if (subnetDetails.get() != null) {
            return subnetId.equals(subnetDetails.get().getSubnetId());
        }
        return false;
    }

    @Override
    public List<MetricFamilySamples> collect() {
        return taskMetaMetric;
    }

    @Override
    public void update() {
        Set<ResourceRelation> newRouting = new HashSet<>();
        List<Sample> taskMetaMetricSamples = new ArrayList<>();
        List<StaticConfig> latestTargets = new ArrayList<>();

        ScrapeConfig scrapeConfig = scrapeConfigProvider.getScrapeConfig();
        log.info("Discovering ECS targets with scrape configs={}", scrapeConfig.getEcsTaskScrapeConfigs());
        try {
            accountProvider.getAccounts().forEach(awsAccount -> awsAccount.getRegions().forEach(region -> {
                String operationName = "EcsClient/listClusters";
                ImmutableSortedMap<String, String> TELEMETRY_LABELS =
                        ImmutableSortedMap.of(
                                SCRAPE_ACCOUNT_ID_LABEL, awsAccount.getAccountId(),
                                SCRAPE_REGION_LABEL, region,
                                SCRAPE_OPERATION_LABEL, operationName,
                                SCRAPE_NAMESPACE_LABEL, "AWS/ECS");
                SortedMap<String, String> labels = new TreeMap<>(TELEMETRY_LABELS);
                try {
                    EcsClient ecsClient = awsClientProvider.getECSClient(region, awsAccount);
                    // List clusters just returns the cluster ARN. There is no need to paginate
                    ListClustersResponse listClustersResponse = rateLimiter.doWithRateLimit(operationName,
                            TELEMETRY_LABELS,
                            ecsClient::listClusters);
                    labels.put(SCRAPE_REGION_LABEL, region);
                    if (listClustersResponse.hasClusterArns()) {
                        listClustersResponse.clusterArns().stream()
                                .map(resourceMapper::map)
                                .filter(Optional::isPresent)
                                .map(Optional::get)
                                .forEach(cluster -> latestTargets.addAll(
                                        buildTargetsInCluster(awsAccount, scrapeConfig, ecsClient, cluster, newRouting,
                                                taskMetaMetricSamples)));
                    }
                } catch (Exception e) {
                    log.error("Failed to get list of ECS Clusters", e);
                }
            }));
        } catch (Exception e) {
            log.error("Failed to build ECS Targets", e);
        }

        routing = newRouting;
        targets = latestTargets.stream()
                .filter(config -> shouldScrapeTargets(scrapeConfig, config))
                .filter(config -> scrapeConfig.keepMetric("up", config.getLabels()))
                .collect(Collectors.toList());

        if (taskMetaMetricSamples.size() > 0) {
            metricSampleBuilder.buildFamily(taskMetaMetricSamples).ifPresent(metricFamilySamples ->
                    taskMetaMetric = Collections.singletonList(metricFamilySamples));
        } else {
            taskMetaMetric = Collections.emptyList();
        }

        if (scrapeConfig.isDiscoverECSTasks()) {
            try {
                File resultFile = new File(scrapeConfig.getEcsTargetSDFile());
                ObjectWriter objectWriter = objectMapperFactory.getObjectMapper().writerWithDefaultPrettyPrinter();
                objectWriter.writeValue(resultFile, targets);
                if (scrapeConfig.isLogECSTargets()) {
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
    boolean shouldScrapeTargets(ScrapeConfig scrapeConfig, StaticConfig config) {
        String targetVpc = config.getLabels().getVpcId();
        String targetSubnet = config.getLabels().getSubnetId();
        boolean vpcOK = scrapeConfig.isDiscoverECSTasksAcrossVPCs() ||
                (subnetDetails.get() != null && subnetDetails.get().getVpcId().equals(targetVpc));
        boolean subnetOK = subnetsToScrape.contains(targetSubnet) ||
                (subnetDetails.get() != null && subnetDetails.get().getSubnetId().equals(targetSubnet)) ||
                !scrapeConfig.isDiscoverOnlySubnetTasks();
        return vpcOK && subnetOK;
    }

    @VisibleForTesting
    List<StaticConfig> buildTargetsInCluster(AWSAccount awsAccount, ScrapeConfig scrapeConfig, EcsClient ecsClient,
                                             Resource cluster,
                                             Set<ResourceRelation> newRouting, List<Sample> taskMetaMetric) {
        List<StaticConfig> targets = new ArrayList<>();
        String nextToken;
        log.info("Discovering ECS targets in cluster={}", cluster.getName());
        do {
            // List services just returns the service ARN. There is no need to paginate
            ListServicesRequest serviceReq = ListServicesRequest.builder()
                    .cluster(cluster.getName())
                    .build();
            String operationName = "EcsClient/listServices";
            ListServicesResponse serviceResp = rateLimiter.doWithRateLimit(operationName,
                    ImmutableSortedMap.of(
                            SCRAPE_ACCOUNT_ID_LABEL, cluster.getAccount(),
                            SCRAPE_REGION_LABEL, cluster.getRegion(),
                            SCRAPE_OPERATION_LABEL, operationName,
                            SCRAPE_NAMESPACE_LABEL, "AWS/ECS"),
                    () -> ecsClient.listServices(serviceReq));
            if (serviceResp.hasServiceArns()) {
                // Get tags
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
                                targets.addAll(
                                        buildTargetsInService(scrapeConfig, ecsClient, cluster, service, tagsByName,
                                                taskMetaMetric));
                            }
                            services.add(service);
                        });
                newRouting.addAll(lbToECSRoutingBuilder.getRoutings(ecsClient, cluster, services));
            }
            nextToken = serviceResp.nextToken();
        } while (nextToken != null);

        Set<String> capturedTasks = targets.stream().map(StaticConfig::getLabels)
                .map(labels -> format("%s-%s-%s-%s", labels.getAccountId(), labels.getRegion(), labels.getCluster(),
                        labels.getPod().substring(labels.getWorkload().length() + 1)))
                .collect(Collectors.toCollection(TreeSet::new));

        // Tasks without service
        do {
            // List services just returns the service ARN. There is no need to paginate
            ListTasksRequest tasksRequest = ListTasksRequest.builder()
                    .cluster(cluster.getName())
                    .build();
            String operationName = "EcsClient/listTasks";
            ListTasksResponse tasksResponse = rateLimiter.doWithRateLimit(operationName,
                    ImmutableSortedMap.of(
                            SCRAPE_ACCOUNT_ID_LABEL, cluster.getAccount(),
                            SCRAPE_REGION_LABEL, cluster.getRegion(),
                            SCRAPE_OPERATION_LABEL, operationName,
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
                            new TreeSet<>(tasksWithoutService), Collections.emptyMap(), taskMetaMetric));
                }
            }
            nextToken = tasksResponse.nextToken();
        } while (nextToken != null);

        return targets;
    }

    @VisibleForTesting
    List<StaticConfig> buildTargetsInService(ScrapeConfig scrapeConfig, EcsClient ecsClient, Resource cluster,
                                             Resource service, Map<String, Resource> resourceWithTags,
                                             List<Sample> taskMetaMetric) {
        log.info("Discovering ECS targets in cluster={}, service={}", cluster.getName(), service.getName());
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
            String operationName = "EcsClient/listTasks";
            ListTasksResponse tasksResp = rateLimiter.doWithRateLimit(operationName,
                    ImmutableSortedMap.of(SCRAPE_ACCOUNT_ID_LABEL, cluster.getAccount(), SCRAPE_REGION_LABEL,
                            cluster.getRegion(), SCRAPE_OPERATION_LABEL, operationName, SCRAPE_NAMESPACE_LABEL,
                            "AWS/ECS"), () -> ecsClient.listTasks(request));

            nextToken = tasksResp.nextToken();
            if (tasksResp.hasTaskArns()) {
                for (String taskArn : tasksResp.taskArns()) {
                    taskARNs.add(taskArn);
                    if (taskARNs.size() == 100) {
                        scrapeTargets.addAll(buildTaskTargets(scrapeConfig, ecsClient, cluster, Optional.of(service),
                                taskARNs, tagLabels, taskMetaMetric));
                        taskARNs = new TreeSet<>();
                    }
                }
            }
        } while (nextToken != null);

        // Either the first batch was less than 100 or this is the last batch
        if (taskARNs.size() > 0) {
            scrapeTargets.addAll(buildTaskTargets(scrapeConfig, ecsClient, cluster, Optional.of(service), taskARNs,
                    tagLabels, taskMetaMetric));
        }
        return scrapeTargets;
    }

    @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
    @VisibleForTesting
    List<StaticConfig> buildTaskTargets(ScrapeConfig scrapeConfig, EcsClient ecsClient, Resource cluster,
                                        Optional<Resource> service, Set<String> taskARNs,
                                        Map<String, String> tagLabels, List<Sample> taskMetaMetric) {
        service.ifPresent(serviceRes ->
                log.info("Building ECS targets in cluster={}, service={}", cluster.getName(), serviceRes.getName()));

        List<StaticConfig> configs = new ArrayList<>();
        DescribeTasksRequest request =
                DescribeTasksRequest.builder().cluster(cluster.getName()).tasks(taskARNs).build();
        String operationName = "EcsClient/describeTasks";
        DescribeTasksResponse taskResponse = rateLimiter.doWithRateLimit(operationName,
                ImmutableSortedMap.of(SCRAPE_ACCOUNT_ID_LABEL, cluster.getAccount(), SCRAPE_REGION_LABEL,
                        cluster.getRegion(), SCRAPE_OPERATION_LABEL, operationName, SCRAPE_NAMESPACE_LABEL,
                        "AWS/ECS"), () -> ecsClient.describeTasks(request));
        if (taskResponse.hasTasks()) {
            configs.addAll(taskResponse.tasks().stream()
                    .filter(ecsTaskUtil::hasAllInfo)
                    .flatMap(task -> {
                        Map<String, String> labels = new TreeMap<>();
                        labels.put(SCRAPE_ACCOUNT_ID_LABEL, cluster.getAccount());
                        labels.put(SCRAPE_REGION_LABEL, cluster.getRegion());
                        labels.put("cluster", cluster.getName());
                        resourceMapper.map(task.taskDefinitionArn()).ifPresent(res -> labels.put("taskdef_family",
                                res.getName()));
                        labels.put("taskdef_version", task.version().toString());
                        service.ifPresent(res -> labels.put("service", res.getName()));
                        resourceMapper.map(task.taskArn()).ifPresent(res -> labels.put("task_id", res.getName()));
                        metricSampleBuilder.buildSingleSample("aws_ecs_task_info", labels, 1.0D)
                                .ifPresent(taskMetaMetric::add);
                        return ecsTaskUtil.buildScrapeTargets(scrapeConfig, ecsClient, cluster, service,
                                task, tagLabels).stream();
                    })
                    .collect(Collectors.toList()));
        }
        return configs;
    }


    @VisibleForTesting
    String getMetaDataURI() {
        return System.getenv(ECS_CONTAINER_METADATA_URI_V4);
    }

    @VisibleForTesting
    void discoverSelfSubnet() {
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
