/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.exporter;

import ai.asserts.aws.cloudwatch.config.ECSTaskDefScrapeConfig;
import ai.asserts.aws.cloudwatch.config.ScrapeConfig;
import ai.asserts.aws.cloudwatch.model.CWNamespace;
import ai.asserts.aws.exporter.ECSServiceDiscoveryExporter.Labels;
import ai.asserts.aws.exporter.ECSServiceDiscoveryExporter.StaticConfig;
import ai.asserts.aws.resource.Resource;
import ai.asserts.aws.resource.ResourceMapper;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
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
import java.util.Optional;

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

    public boolean hasAllInfo(Task task) {
        return "RUNNING".equals(task.lastStatus()) && task.hasAttachments() && task.attachments()
                .stream()
                .filter(attachment -> attachment.type().equals(ENI) && attachment.hasDetails())
                .flatMap(attachment -> attachment.details().stream())
                .anyMatch(detail -> detail.name().equals(PRIVATE_IPv4ADDRESS));
    }

    public Optional<StaticConfig> buildScrapeTarget(ScrapeConfig scrapeConfig, EcsClient ecsClient,
                                                    Resource cluster, Resource service, Task task) {
        StaticConfig.StaticConfigBuilder staticConfigBuilder = StaticConfig.builder();

        String ipAddress = "unknown";
        Integer port = -1;
        Resource taskDefResource = resourceMapper.map(task.taskDefinitionArn())
                .orElseThrow(() -> new RuntimeException("Unknown resource ARN: " + task.taskDefinitionArn()));
        Resource taskResource = resourceMapper.map(task.taskArn())
                .orElseThrow(() -> new RuntimeException("Unknown resource ARN: " + task.taskArn()));

        Labels.LabelsBuilder labelsBuilder = Labels.builder()
                .cluster(cluster.getName())
                .job(service.getName())
                .taskDefName(taskDefResource.getName())
                .taskDefVersion(taskDefResource.getVersion())
                .taskId(taskResource.getName())
                .metricsPath("/metrics");


        Optional<ECSTaskDefScrapeConfig> defOpt = scrapeConfig.getECSScrapeConfig(taskDefResource);
        String containerName = defOpt.map(ECSTaskDefScrapeConfig::getContainerDefinitionName).orElse(null);

        Optional<KeyValuePair> ipAddressOpt = task.attachments().stream()
                .filter(attachment -> attachment.type().equals(ENI) && attachment.hasDetails())
                .flatMap(attachment -> attachment.details().stream())
                .filter(detail -> detail.name().equals(PRIVATE_IPv4ADDRESS))
                .findFirst();
        if (ipAddressOpt.isPresent()) {
            ipAddress = ipAddressOpt.get().value();
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

            if (taskDefinition.hasContainerDefinitions()) {
                List<ContainerDefinition> containerDefinitions = taskDefinition.containerDefinitions();
                List<PortMapping> portMappings;
                if (containerDefinitions.size() == 1) {
                    portMappings = containerDefinitions.get(0).portMappings();
                } else {
                    portMappings = containerDefinitions.stream()
                            .filter(containerDefinition -> containerDefinition.name().equals(containerName))
                            .map(ContainerDefinition::portMappings)
                            .findFirst().orElse(Collections.emptyList());
                }

                Optional<PortMapping> specifiedPort = Optional.empty();
                if (defOpt.isPresent()) {
                    ECSTaskDefScrapeConfig taskDefConfig = defOpt.get();
                    if (taskDefConfig.getContainerPort() != null) {
                        specifiedPort = portMappings.stream()
                                .filter(mapping -> mapping.containerPort().equals(taskDefConfig.getContainerPort()))
                                .findFirst();
                    }
                    if (!specifiedPort.isPresent()) {
                        log.warn("No scrape target port found as per configuration {}", taskDefConfig);
                    }
                    labelsBuilder = labelsBuilder.metricsPath(taskDefConfig.getMetricPath());
                }

                if (specifiedPort.isPresent()) {
                    port = specifiedPort.get().hostPort();
                } else if (portMappings.size() == 1) {
                    log.warn("For task {} in Cluster {}, Service {}, Task Definition {} picking only known port ",
                            task.taskArn(), cluster.getName(), service.getName(), taskDefResource.getName());
                    port = portMappings.get(0).hostPort();
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
                .targets(ImmutableSet.of(format("%s:%d", ipAddress, port)))
                .build());
    }
}
