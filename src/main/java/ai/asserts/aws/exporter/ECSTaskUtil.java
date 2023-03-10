/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.exporter;

import ai.asserts.aws.AWSClientProvider;
import ai.asserts.aws.AccountProvider.AWSAccount;
import ai.asserts.aws.RateLimiter;
import ai.asserts.aws.config.ECSTaskDefScrapeConfig;
import ai.asserts.aws.config.ScrapeConfig;
import ai.asserts.aws.config.ScrapeConfig.SubnetDetails;
import ai.asserts.aws.exporter.ECSServiceDiscoveryExporter.StaticConfig;
import ai.asserts.aws.exporter.Labels.LabelsBuilder;
import ai.asserts.aws.resource.Resource;
import ai.asserts.aws.resource.ResourceMapper;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableSortedMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.DescribeSubnetsRequest;
import software.amazon.awssdk.services.ec2.model.DescribeSubnetsResponse;
import software.amazon.awssdk.services.ecs.EcsClient;
import software.amazon.awssdk.services.ecs.model.ContainerDefinition;
import software.amazon.awssdk.services.ecs.model.DescribeTaskDefinitionRequest;
import software.amazon.awssdk.services.ecs.model.DescribeTasksRequest;
import software.amazon.awssdk.services.ecs.model.DescribeTasksResponse;
import software.amazon.awssdk.services.ecs.model.KeyValuePair;
import software.amazon.awssdk.services.ecs.model.Task;
import software.amazon.awssdk.services.ecs.model.TaskDefinition;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static ai.asserts.aws.MetricNameUtil.SCRAPE_ACCOUNT_ID_LABEL;
import static ai.asserts.aws.MetricNameUtil.SCRAPE_NAMESPACE_LABEL;
import static ai.asserts.aws.MetricNameUtil.SCRAPE_OPERATION_LABEL;
import static ai.asserts.aws.MetricNameUtil.SCRAPE_REGION_LABEL;
import static java.lang.String.format;

@Component
@Slf4j
public class ECSTaskUtil {
    private final AWSClientProvider awsClientProvider;
    private final ResourceMapper resourceMapper;
    private final RateLimiter rateLimiter;

    private final String envName;

    private final Map<String, String> subnetIdMap = new ConcurrentHashMap<>();
    private final Map<String, SubnetDetails> taskSubnetMap = new ConcurrentHashMap<>();
    public static final String ENI = "ElasticNetworkInterface";
    public static final String PRIVATE_IPv4ADDRESS = "privateIPv4Address";
    public static final String SUBNET_ID = "subnetId";
    public static final String PROMETHEUS_PORT_DOCKER_LABEL = "PROMETHEUS_EXPORTER_PORT";
    public static final String PROMETHEUS_METRIC_PATH_DOCKER_LABEL = "PROMETHEUS_EXPORTER_PATH";

    public ECSTaskUtil(AWSClientProvider awsClientProvider, ResourceMapper resourceMapper, RateLimiter rateLimiter) {
        this.awsClientProvider = awsClientProvider;
        this.resourceMapper = resourceMapper;
        this.rateLimiter = rateLimiter;
        // If the exporter's environment name is marked, use this for ECS metrics
        envName = getInstallEnvName();
    }

    @VisibleForTesting
    String getInstallEnvName() {
        final String envName;
        String INSTALLED_ENV_NAME = "INSTALL_ENV_NAME";
        if (System.getenv(INSTALLED_ENV_NAME) != null) {
            envName = System.getenv(INSTALLED_ENV_NAME);
        } else {
            envName = null;
        }
        return envName;
    }

    public boolean hasAllInfo(Task task) {
        return "RUNNING".equals(task.lastStatus()) && task.hasAttachments() && task.attachments()
                .stream()
                .filter(attachment -> attachment.type().equals(ENI) && attachment.hasDetails())
                .flatMap(attachment -> attachment.details().stream())
                .anyMatch(detail -> detail.name().equals(PRIVATE_IPv4ADDRESS));
    }

    @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
    public List<StaticConfig> buildScrapeTargets(ScrapeConfig scrapeConfig, EcsClient ecsClient,
                                                 Resource cluster, Optional<Resource> service, Task task) {
        return buildScrapeTargets(scrapeConfig, ecsClient, cluster, service, task, Collections.emptyMap());
    }

    @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
    public List<StaticConfig> buildScrapeTargets(ScrapeConfig scrapeConfig, EcsClient ecsClient,
                                                 Resource cluster, Optional<Resource> service, Task task,
                                                 Map<String, String> tagLabels) {
        Map<Labels, StaticConfig> targetsByLabel = new LinkedHashMap<>();

        String ipAddress;
        Resource taskDefResource = resourceMapper.map(task.taskDefinitionArn())
                .orElseThrow(() -> new RuntimeException("Unknown resource ARN: " + task.taskDefinitionArn()));
        Resource taskResource = resourceMapper.map(task.taskArn())
                .orElseThrow(() -> new RuntimeException("Unknown resource ARN: " + task.taskArn()));

        LabelsBuilder labelsBuilder;
        taskSubnetMap.computeIfAbsent(taskResource.getName(), k -> getSubnetDetails(task, taskResource));
        if (service.isPresent()) {
            labelsBuilder = Labels.builder()
                    .workload(service.get().getName())
                    .taskId(taskResource.getName())
                    .pod(service.get().getName() + "-" + taskResource.getName())
                    .vpcId(taskSubnetMap.get(taskResource.getName()).getVpcId())
                    .subnetId(taskSubnetMap.get(taskResource.getName()).getSubnetId())
                    .accountId(cluster.getAccount())
                    .region(cluster.getRegion())
                    .cluster(cluster.getName())
                    .env(envName != null ? envName : cluster.getAccount())
                    .site(cluster.getRegion())
                    .taskDefName(taskDefResource.getName())
                    .taskDefVersion(taskDefResource.getVersion())
                    .metricsPath("/metrics");
        } else {
            labelsBuilder = Labels.builder()
                    .workload(taskDefResource.getName())
                    .taskId(taskResource.getName())
                    .pod(taskDefResource.getName() + "-" + taskResource.getName())
                    .vpcId(taskSubnetMap.get(taskResource.getName()).getVpcId())
                    .subnetId(taskSubnetMap.get(taskResource.getName()).getSubnetId())
                    .accountId(cluster.getAccount())
                    .region(cluster.getRegion())
                    .cluster(cluster.getName())
                    .env(envName != null ? envName : cluster.getAccount())
                    .site(cluster.getRegion())
                    .taskDefName(taskDefResource.getName())
                    .taskDefVersion(taskDefResource.getVersion())
                    .metricsPath("/metrics");
        }

        Optional<KeyValuePair> ipAddressOpt = task.attachments().stream()
                .filter(attachment -> attachment.type().equals(ENI) && attachment.hasDetails())
                .flatMap(attachment -> attachment.details().stream())
                .filter(detail -> detail.name().equals(PRIVATE_IPv4ADDRESS))
                .findFirst();
        if (ipAddressOpt.isPresent()) {
            ipAddress = ipAddressOpt.get().value();
        } else {
            log.error("Couldn't find IP address of task instance for task   {}", task.taskArn());
            return Collections.emptyList();
        }

        try {
            TaskDefinition taskDefinition = rateLimiter.doWithRateLimit("EcsClient/describeTaskDefinition",
                    ImmutableSortedMap.of(
                            SCRAPE_ACCOUNT_ID_LABEL, cluster.getAccount(),
                            SCRAPE_REGION_LABEL, cluster.getRegion(),
                            SCRAPE_OPERATION_LABEL, "describeTaskDefinition",
                            SCRAPE_NAMESPACE_LABEL, "AWS/ECS"
                    ), () ->
                            ecsClient.describeTaskDefinition(DescribeTaskDefinitionRequest.builder()
                                    .taskDefinition(task.taskDefinitionArn())
                                    .build()).taskDefinition()
            );

            Map<String, Map<Integer, ECSTaskDefScrapeConfig>> configs = scrapeConfig.getECSConfigByNameAndPort();
            if (taskDefinition.hasContainerDefinitions()) {
                // In all cases, if a container config is specified, we use the specified port and path
                taskDefinition.containerDefinitions().forEach(cD -> {
                    Optional<String> pathFromLabel = getDockerLabel(cD, PROMETHEUS_METRIC_PATH_DOCKER_LABEL);
                    Optional<String> portFromLabel = getDockerLabel(cD, PROMETHEUS_PORT_DOCKER_LABEL);
                    labelsBuilder.availabilityZone(task.availabilityZone());
                    String jobName = cD.name();
                    if (configs.containsKey(cD.name())) {
                        Map<Integer, ECSTaskDefScrapeConfig> byPort = configs.get(cD.name());
                        ECSTaskDefScrapeConfig forAnyPort = byPort.get(-1);
                        cD.portMappings().forEach(port -> {
                            Labels labels;
                            if (byPort.get(port.containerPort()) != null) {
                                labels = labelsBuilder
                                        .job(jobName)
                                        .metricsPath(byPort.get(port.containerPort()).getMetricPath())
                                        .container(cD.name())
                                        .build();
                            } else if (forAnyPort != null) {
                                labels = labelsBuilder
                                        .job(jobName)
                                        .metricsPath(forAnyPort.getMetricPath())
                                        .container(cD.name())
                                        .build();
                            } else if (scrapeConfig.isDiscoverAllECSTasksByDefault()) {
                                labels = labelsBuilder
                                        .job(jobName)
                                        .metricsPath("/metrics")
                                        .container(cD.name())
                                        .build();
                            } else {
                                labels = null;
                            }

                            if (labels != null) {
                                labels.populateMapEntries();
                                labels.putAll(tagLabels);
                                Map<String, String> afterRelabeling = scrapeConfig.additionalLabels("up", labels);
                                labels.putAll(afterRelabeling);
                                StaticConfig staticConfig = targetsByLabel.computeIfAbsent(
                                        labels, k -> StaticConfig.builder().labels(labels).build());
                                staticConfig.getTargets().add(format("%s:%d", ipAddress, port.hostPort()));
                            }
                        });
                    } else if (pathFromLabel.isPresent() && portFromLabel.isPresent()) {
                        Labels labels = labelsBuilder
                                .job(jobName)
                                .metricsPath(pathFromLabel.get())
                                .container(cD.name())
                                .build();
                        labels.populateMapEntries();
                        labels.putAll(tagLabels);
                        Map<String, String> afterRelabeling = scrapeConfig.additionalLabels("up", labels);
                        labels.putAll(afterRelabeling);
                        StaticConfig staticConfig = targetsByLabel.computeIfAbsent(
                                labels, k -> StaticConfig.builder().labels(labels).build());
                        staticConfig.getTargets().add(format("%s:%s", ipAddress, portFromLabel.get()));
                    } else if (scrapeConfig.isDiscoverAllECSTasksByDefault()) {
                        Labels labels = labelsBuilder
                                .job(jobName)
                                .metricsPath("/metrics")
                                .container(cD.name())
                                .build();
                        labels.populateMapEntries();
                        labels.putAll(tagLabels);
                        Map<String, String> afterRelabeling = scrapeConfig.additionalLabels("up", labels);
                        labels.putAll(afterRelabeling);
                        StaticConfig staticConfig = targetsByLabel.computeIfAbsent(
                                labels, k -> StaticConfig.builder().labels(labels).build());
                        cD.portMappings().forEach(port ->
                                staticConfig.getTargets().add(format("%s:%d", ipAddress, port.hostPort())));
                    }
                });
            }
        } catch (
                Exception e) {
            log.error("Failed to describe task definition", e);
            return Collections.emptyList();
        }

        return targetsByLabel.values().stream()
                .filter(config -> config.getTargets().size() > 0).collect(Collectors.toList());
    }

    public SubnetDetails getSubnetDetails(Resource taskResource) {
        EcsClient ecsClient = awsClientProvider.getECSClient(taskResource.getRegion(), AWSAccount.builder()
                .accountId(taskResource.getAccount())
                .build());
        DescribeTasksResponse response = rateLimiter.doWithRateLimit("EcsClient/describeTasks",
                ImmutableSortedMap.of(
                        SCRAPE_ACCOUNT_ID_LABEL, taskResource.getAccount(),
                        SCRAPE_REGION_LABEL, taskResource.getRegion(),
                        SCRAPE_OPERATION_LABEL, "EcsClient/describeTasks"),
                () -> ecsClient.describeTasks(DescribeTasksRequest.builder()
                        .cluster(taskResource.getChildOf().getName())
                        .tasks(taskResource.getArn())
                        .build()));
        if (response.hasTasks()) {
            return response.tasks().get(0).attachments().stream()
                    .filter(attachment -> attachment.type().equals("ElasticNetworkInterface"))
                    .findFirst()
                    .flatMap(attachment -> attachment.details().stream()
                            .filter(kv -> kv.name().equals("subnetId")).findFirst())
                    .map(kv -> {
                        AtomicReference<String> vpcId = new AtomicReference<>("");
                        AtomicReference<String> subnetId = new AtomicReference<>("");
                        subnetId.set(kv.value());
                        vpcId.set(subnetIdMap.computeIfAbsent(subnetId.get(), kk ->
                                getVpcId(taskResource, subnetId)));
                        return SubnetDetails.builder()
                                .vpcId(vpcId.get())
                                .subnetId(subnetId.get())
                                .build();
                    }).orElse(null);
        }
        log.warn("Failed to find description for {}", taskResource);
        return null;
    }

    private SubnetDetails getSubnetDetails(Task task, Resource taskResource) {
        return task.attachments().stream()
                .filter(attachment -> attachment.type().equals("ElasticNetworkInterface"))
                .findFirst()
                .flatMap(attachment -> attachment.details().stream()
                        .filter(kv -> kv.name().equals("subnetId")).findFirst())
                .map(kv -> {
                    AtomicReference<String> vpcId = new AtomicReference<>("");
                    AtomicReference<String> subnetId = new AtomicReference<>("");
                    subnetId.set(kv.value());
                    vpcId.set(subnetIdMap.computeIfAbsent(subnetId.get(), kk ->
                            getVpcId(taskResource, subnetId)));
                    return SubnetDetails.builder()
                            .vpcId(vpcId.get())
                            .subnetId(subnetId.get())
                            .build();
                }).orElse(null);
    }

    private String getVpcId(Resource taskResource, AtomicReference<String> subnetId) {
        AtomicReference<String> id = new AtomicReference<>("");
        Ec2Client ec2Client = awsClientProvider.getEc2Client(taskResource.getRegion(),
                AWSAccount.builder()
                        .accountId(taskResource.getAccount())
                        .build());
        DescribeSubnetsResponse r = rateLimiter.doWithRateLimit("EC2Client/describeSubnets",
                ImmutableSortedMap.of(
                        SCRAPE_ACCOUNT_ID_LABEL, taskResource.getAccount(),
                        SCRAPE_REGION_LABEL, taskResource.getRegion(),
                        SCRAPE_OPERATION_LABEL, "EC2Client/describeSubnets"
                ),
                () -> ec2Client.describeSubnets(DescribeSubnetsRequest.builder()
                        .subnetIds(subnetId.get())
                        .build()));
        r.subnets().stream().findFirst().ifPresent(subnet -> id.set(subnet.vpcId()));
        return id.get();
    }

    Optional<String> getDockerLabel(ContainerDefinition container, String labelName) {
        return container.dockerLabels().entrySet().stream()
                .filter(entry -> entry.getKey().equals(labelName))
                .map(Map.Entry::getValue)
                .findFirst();
    }
}
