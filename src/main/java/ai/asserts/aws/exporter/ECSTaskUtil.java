/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.exporter;

import ai.asserts.aws.RateLimiter;
import ai.asserts.aws.cloudwatch.config.ECSTaskDefScrapeConfig;
import ai.asserts.aws.cloudwatch.config.ScrapeConfig;
import ai.asserts.aws.exporter.ECSServiceDiscoveryExporter.Labels;
import ai.asserts.aws.exporter.ECSServiceDiscoveryExporter.Labels.LabelsBuilder;
import ai.asserts.aws.exporter.ECSServiceDiscoveryExporter.StaticConfig;
import ai.asserts.aws.exporter.ECSServiceDiscoveryExporter.StaticConfig.StaticConfigBuilder;
import ai.asserts.aws.resource.Resource;
import ai.asserts.aws.resource.ResourceMapper;
import com.google.common.collect.ImmutableSortedMap;
import io.micrometer.core.instrument.util.StringUtils;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import software.amazon.awssdk.services.ecs.EcsClient;
import software.amazon.awssdk.services.ecs.model.ContainerDefinition;
import software.amazon.awssdk.services.ecs.model.DescribeTaskDefinitionRequest;
import software.amazon.awssdk.services.ecs.model.KeyValuePair;
import software.amazon.awssdk.services.ecs.model.PortMapping;
import software.amazon.awssdk.services.ecs.model.Task;
import software.amazon.awssdk.services.ecs.model.TaskDefinition;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import static ai.asserts.aws.MetricNameUtil.SCRAPE_NAMESPACE_LABEL;
import static ai.asserts.aws.MetricNameUtil.SCRAPE_OPERATION_LABEL;
import static ai.asserts.aws.MetricNameUtil.SCRAPE_REGION_LABEL;
import static java.lang.String.format;

@Component
@Slf4j
@AllArgsConstructor
public class ECSTaskUtil {
    private final ResourceMapper resourceMapper;
    private final RateLimiter rateLimiter;
    public static final String ENI = "ElasticNetworkInterface";
    public static final String PRIVATE_IPv4ADDRESS = "privateIPv4Address";
    public static final String PROMETHEUS_PORT_DOCKER_LABEL = "PROMETHEUS_EXPORTER_PORT";
    public static final String PROMETHEUS_METRIC_PATH_DOCKER_LABEL = "PROMETHEUS_EXPORTER_PATH";

    public boolean hasAllInfo(Task task) {
        return "RUNNING".equals(task.lastStatus()) && task.hasAttachments() && task.attachments()
                .stream()
                .filter(attachment -> attachment.type().equals(ENI) && attachment.hasDetails())
                .flatMap(attachment -> attachment.details().stream())
                .anyMatch(detail -> detail.name().equals(PRIVATE_IPv4ADDRESS));
    }

    public Optional<StaticConfig> buildScrapeTarget(ScrapeConfig scrapeConfig, EcsClient ecsClient,
                                                    Resource cluster, Resource service, Task task) {
        StaticConfigBuilder staticConfigBuilder = StaticConfig.builder();

        String ipAddress;
        Set<String> targets = new TreeSet<>();
        Resource taskDefResource = resourceMapper.map(task.taskDefinitionArn())
                .orElseThrow(() -> new RuntimeException("Unknown resource ARN: " + task.taskDefinitionArn()));
        Resource taskResource = resourceMapper.map(task.taskArn())
                .orElseThrow(() -> new RuntimeException("Unknown resource ARN: " + task.taskArn()));

        LabelsBuilder labelsBuilder = Labels.builder()
                .region(cluster.getRegion())
                .cluster(cluster.getName())
                .job(service.getName())
                .taskDefName(taskDefResource.getName())
                .taskDefVersion(taskDefResource.getVersion())
                .taskId(taskResource.getName())
                .metricsPath("/metrics");

        Optional<KeyValuePair> ipAddressOpt = task.attachments().stream()
                .filter(attachment -> attachment.type().equals(ENI) && attachment.hasDetails())
                .flatMap(attachment -> attachment.details().stream())
                .filter(detail -> detail.name().equals(PRIVATE_IPv4ADDRESS))
                .findFirst();
        if (ipAddressOpt.isPresent()) {
            ipAddress = ipAddressOpt.get().value();
        } else {
            log.error("Couldn't find IP address of task instance for task   {}", task.taskArn());
            return Optional.empty();
        }

        try {
            TaskDefinition taskDefinition = rateLimiter.doWithRateLimit("EcsClient/describeTaskDefinition",
                    ImmutableSortedMap.of(
                            SCRAPE_REGION_LABEL, cluster.getRegion(),
                            SCRAPE_OPERATION_LABEL, "describeTaskDefinition",
                            SCRAPE_NAMESPACE_LABEL, "AWS/ECS"
                    ), () ->
                            ecsClient.describeTaskDefinition(DescribeTaskDefinitionRequest.builder()
                                    .taskDefinition(task.taskDefinitionArn())
                                    .build()).taskDefinition()
            );

            // Build targets using docker labels if present
            getTargetUsingDockerLabels(ipAddress, taskDefinition).ifPresent(targets::add);
            Optional<String> metricPathUsingDockerLabel = getMetricPathUsingDockerLabel(taskDefinition);
            if (metricPathUsingDockerLabel.isPresent()) {
                labelsBuilder = labelsBuilder.metricsPath(metricPathUsingDockerLabel.get());
            }

            Optional<ECSTaskDefScrapeConfig> defOpt = scrapeConfig.getECSScrapeConfig(taskDefinition);
            Optional<String> containerNameOpt = defOpt.map(ECSTaskDefScrapeConfig::getContainerDefinitionName);

            // Metric Path
            if (defOpt.isPresent() && StringUtils.isNotEmpty(defOpt.get().getMetricPath())) {
                labelsBuilder = labelsBuilder.metricsPath(defOpt.get().getMetricPath());
            }

            if (taskDefinition.hasContainerDefinitions()) {
                List<ContainerDefinition> containerDefinitions = taskDefinition.containerDefinitions();
                if (defOpt.isPresent() && containerNameOpt.isPresent()) {
                    Optional<ContainerDefinition> matchingContainer = containerDefinitions.stream()
                            .filter(containerDefinition -> containerDefinition.name().equals(containerNameOpt.get()))
                            .findFirst();
                    if (matchingContainer.isPresent() && !CollectionUtils.isEmpty(matchingContainer.get().portMappings())) {
                        if (matchingContainer.get().portMappings().size() > 1) {
                            matchingContainer.get().portMappings().stream()
                                    .filter(portMapping -> portMapping.containerPort()
                                            .equals(defOpt.get().getContainerPort()))
                                    .map(PortMapping::hostPort)
                                    .findFirst()
                                    .ifPresent(port -> targets.add(format("%s:%d", ipAddress, port)));
                        } else {
                            PortMapping portMapping = matchingContainer.get().portMappings().get(0);
                            targets.add(format("%s:%d", ipAddress, portMapping.hostPort()));
                        }
                    }
                }
            }
        } catch (
                Exception e) {
            log.error("Failed to describe task definition", e);
            return Optional.empty();
        }

        if (targets.size() == 1) {
            return Optional.of(staticConfigBuilder
                    .labels(labelsBuilder.build())
                    .targets(targets)
                    .build());
        } else {
            log.warn("No targets in service {}", service);
            return Optional.empty();
        }

    }

    Optional<String> getTargetUsingDockerLabels(String ipAddress, TaskDefinition taskDefinition) {
        Optional<String> target = Optional.empty();
        if (taskDefinition.hasContainerDefinitions()) {
            List<ContainerDefinition> containerDefinitions = taskDefinition.containerDefinitions();
            for (ContainerDefinition container : containerDefinitions) {
                Optional<String> portLabel = container.dockerLabels().entrySet().stream()
                        .filter(entry -> entry.getKey().equals(PROMETHEUS_PORT_DOCKER_LABEL))
                        .map(Map.Entry::getValue)
                        .findFirst();

                if (portLabel.isPresent()) {
                    Optional<PortMapping> portMappingOpt = container.portMappings().stream()
                            .filter(portMapping -> portMapping.containerPort().toString().equals(portLabel.get()))
                            .findFirst();
                    if (portMappingOpt.isPresent()) {
                        target = Optional.of(format("%s:%s", ipAddress, portMappingOpt.get().hostPort()));
                        break;
                    }
                }
            }
        }
        return target;
    }

    Optional<String> getMetricPathUsingDockerLabel(TaskDefinition taskDefinition) {
        Optional<String> path = Optional.empty();
        if (taskDefinition.hasContainerDefinitions()) {
            List<ContainerDefinition> containerDefinitions = taskDefinition.containerDefinitions();
            for (ContainerDefinition container : containerDefinitions) {
                path = container.dockerLabels().entrySet().stream()
                        .filter(entry -> entry.getKey().equals(PROMETHEUS_METRIC_PATH_DOCKER_LABEL))
                        .map(Map.Entry::getValue)
                        .findFirst();
                if (path.isPresent()) {
                    break;
                }
            }
        }
        return path;
    }
}
