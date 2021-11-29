/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.exporter;

import ai.asserts.aws.cloudwatch.config.ECSTaskDefScrapeConfig;
import ai.asserts.aws.cloudwatch.config.ScrapeConfig;
import ai.asserts.aws.cloudwatch.model.CWNamespace;
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
import software.amazon.awssdk.services.ecs.EcsClient;
import software.amazon.awssdk.services.ecs.model.ContainerDefinition;
import software.amazon.awssdk.services.ecs.model.DescribeTaskDefinitionRequest;
import software.amazon.awssdk.services.ecs.model.KeyValuePair;
import software.amazon.awssdk.services.ecs.model.PortMapping;
import software.amazon.awssdk.services.ecs.model.Task;
import software.amazon.awssdk.services.ecs.model.TaskDefinition;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import static ai.asserts.aws.MetricNameUtil.SCRAPE_ERROR_COUNT_METRIC;
import static ai.asserts.aws.MetricNameUtil.SCRAPE_LATENCY_METRIC;
import static ai.asserts.aws.MetricNameUtil.SCRAPE_NAMESPACE_LABEL;
import static ai.asserts.aws.MetricNameUtil.SCRAPE_OPERATION_LABEL;
import static ai.asserts.aws.MetricNameUtil.SCRAPE_REGION_LABEL;
import static java.lang.String.format;

@Component
@Slf4j
@AllArgsConstructor
public class ECSTaskUtil {
    private final ResourceMapper resourceMapper;
    private final BasicMetricCollector metricCollector;
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

        ImmutableSortedMap<String, String> telemetryLabels = ImmutableSortedMap.of(
                SCRAPE_NAMESPACE_LABEL, CWNamespace.ecs_svc.getNormalizedNamespace(),
                SCRAPE_OPERATION_LABEL, "describeTaskDefinition",
                SCRAPE_REGION_LABEL, cluster.getRegion()
        );

        try {
            long tick = System.currentTimeMillis();
            TaskDefinition taskDefinition = ecsClient.describeTaskDefinition(DescribeTaskDefinitionRequest.builder()
                    .taskDefinition(task.taskDefinitionArn())
                    .build()).taskDefinition();
            tick = System.currentTimeMillis() - tick;
            metricCollector.recordLatency(SCRAPE_LATENCY_METRIC, telemetryLabels, tick);

            // Build targets using docker labels if present
            getTargetUsingDockerLabels(ipAddress, taskDefinition).ifPresent(targets::add);
            Optional<String> metricPathUsingDockerLabel = getMetricPathUsingDockerLabel(taskDefinition);
            if (metricPathUsingDockerLabel.isPresent()) {
                labelsBuilder = labelsBuilder.metricsPath(metricPathUsingDockerLabel.get());
            }

            Optional<ECSTaskDefScrapeConfig> defOpt = scrapeConfig.getECSScrapeConfig(taskDefResource);
            String containerName = defOpt.map(ECSTaskDefScrapeConfig::getContainerDefinitionName).orElse(null);
            if (defOpt.isPresent() && taskDefinition.hasContainerDefinitions()) {
                List<ContainerDefinition> containerDefinitions = taskDefinition.containerDefinitions();
                List<PortMapping> portMappings = Collections.emptyList();
                if (containerDefinitions.size() == 1) {
                    portMappings = containerDefinitions.get(0).portMappings();
                } else {
                    Optional<ContainerDefinition> container = containerDefinitions.stream()
                            .filter(containerDefinition -> containerDefinition.name().equals(containerName))
                            .findFirst();
                    if (container.isPresent()) {
                        portMappings = container.get().portMappings();
                    }
                }

                Optional<PortMapping> specifiedPort = Optional.empty();
                ECSTaskDefScrapeConfig taskDefConfig = defOpt.get();
                if (taskDefConfig.getContainerPort() != null) {
                    specifiedPort = portMappings.stream()
                            .filter(mapping -> mapping.containerPort().equals(taskDefConfig.getContainerPort()))
                            .findFirst();
                }
                if (!specifiedPort.isPresent()) {
                    log.warn("No scrape target port found as per configuration {}", taskDefConfig);
                }
                if (StringUtils.isNotEmpty(taskDefConfig.getMetricPath())) {
                    labelsBuilder = labelsBuilder.metricsPath(taskDefConfig.getMetricPath());
                }

                if (specifiedPort.isPresent()) {
                    targets.add(format("%s:%d", ipAddress, specifiedPort.get().hostPort()));
                } else if (portMappings.size() == 1) {
                    log.warn("For task {} in Cluster {}, Service {}, Task Definition {} picking only known port ",
                            task.taskArn(), cluster.getName(), service.getName(), taskDefResource.getName());
                    targets.add(format("%s:%d", ipAddress, portMappings.get(0).hostPort()));
                } else {
                    log.error("For task {} in Cluster {}, Service {}, Task Definition {} could not pick scrape port ",
                            task.taskArn(), cluster.getName(), service.getName(), taskDefResource.getName());
                    return Optional.empty();
                }
            }
        } catch (Exception e) {
            metricCollector.recordCounterValue(SCRAPE_ERROR_COUNT_METRIC, telemetryLabels, 1);
            log.error("Failed to describe task definition", e);
            return Optional.empty();
        }
        return Optional.of(staticConfigBuilder
                .labels(labelsBuilder.build())
                .targets(targets)
                .build());
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
                if( path.isPresent()) {
                    break;
                }
            }
        }
        return path;
    }
}
