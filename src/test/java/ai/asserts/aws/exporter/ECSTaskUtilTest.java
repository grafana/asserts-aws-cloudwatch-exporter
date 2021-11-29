/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.exporter;

import ai.asserts.aws.cloudwatch.config.ECSTaskDefScrapeConfig;
import ai.asserts.aws.cloudwatch.config.ScrapeConfig;
import ai.asserts.aws.exporter.ECSServiceDiscoveryExporter.StaticConfig;
import ai.asserts.aws.resource.Resource;
import ai.asserts.aws.resource.ResourceMapper;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import org.easymock.EasyMockSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.ecs.EcsClient;
import software.amazon.awssdk.services.ecs.model.Attachment;
import software.amazon.awssdk.services.ecs.model.ContainerDefinition;
import software.amazon.awssdk.services.ecs.model.DescribeTaskDefinitionRequest;
import software.amazon.awssdk.services.ecs.model.DescribeTaskDefinitionResponse;
import software.amazon.awssdk.services.ecs.model.KeyValuePair;
import software.amazon.awssdk.services.ecs.model.PortMapping;
import software.amazon.awssdk.services.ecs.model.Task;
import software.amazon.awssdk.services.ecs.model.TaskDefinition;

import java.util.Optional;

import static ai.asserts.aws.MetricNameUtil.SCRAPE_ERROR_COUNT_METRIC;
import static ai.asserts.aws.MetricNameUtil.SCRAPE_LATENCY_METRIC;
import static ai.asserts.aws.exporter.ECSTaskUtil.ENI;
import static ai.asserts.aws.exporter.ECSTaskUtil.PRIVATE_IPv4ADDRESS;
import static ai.asserts.aws.exporter.ECSTaskUtil.PROMETHEUS_METRIC_PATH_DOCKER_LABEL;
import static ai.asserts.aws.exporter.ECSTaskUtil.PROMETHEUS_PORT_DOCKER_LABEL;
import static org.easymock.EasyMock.anyLong;
import static org.easymock.EasyMock.anyObject;
import static org.easymock.EasyMock.eq;
import static org.easymock.EasyMock.expect;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ECSTaskUtilTest extends EasyMockSupport {
    private ResourceMapper resourceMapper;
    private BasicMetricCollector metricCollector;
    private EcsClient ecsClient;
    private ScrapeConfig scrapeConfig;
    private ECSTaskDefScrapeConfig taskDefScrapeConfig;
    private ECSTaskUtil testClass;
    private Resource cluster;
    private Resource service;
    private Resource task;
    private Resource taskDef;

    @BeforeEach
    public void setup() {
        resourceMapper = mock(ResourceMapper.class);
        metricCollector = mock(BasicMetricCollector.class);
        ecsClient = mock(EcsClient.class);
        scrapeConfig = mock(ScrapeConfig.class);
        taskDefScrapeConfig = mock(ECSTaskDefScrapeConfig.class);
        testClass = new ECSTaskUtil(resourceMapper, metricCollector);

        cluster = Resource.builder()
                .name("cluster")
                .region("us-west-2")
                .build();
        service = Resource.builder()
                .name("service")
                .region("us-west-2")
                .build();
        task = Resource.builder()
                .name("task-id")
                .region("us-west-2")
                .build();
        taskDef = Resource.builder()
                .name("task-def")
                .version("5")
                .region("us-west-2")
                .build();
    }

    @Test
    public void hasAllInfo_false() {
        replayAll();
        assertFalse(testClass.hasAllInfo(Task.builder().build()));
        verifyAll();
    }

    @Test
    public void hasAllInfo_true() {
        replayAll();
        assertTrue(testClass.hasAllInfo(Task.builder()
                .lastStatus("RUNNING")
                .attachments(Attachment.builder()
                        .type(ENI)
                        .details(KeyValuePair.builder()
                                .name(PRIVATE_IPv4ADDRESS)
                                .value("10.20.30.40")
                                .build())
                        .build())
                .build()));
        verifyAll();
    }

    @Test
    public void buildScrapeTarget_use_taskdef_docker_labels_config_success() {
        expect(resourceMapper.map("task-def-arn")).andReturn(Optional.of(taskDef));
        expect(resourceMapper.map("task-arn")).andReturn(Optional.of(task));

        expect(scrapeConfig.getECSScrapeConfig(taskDef)).andReturn(Optional.empty());

        expect(ecsClient.describeTaskDefinition(DescribeTaskDefinitionRequest.builder()
                .taskDefinition("task-def-arn")
                .build())).andReturn(DescribeTaskDefinitionResponse.builder()
                .taskDefinition(TaskDefinition.builder()
                        .containerDefinitions(ContainerDefinition.builder()
                                        .name("model-builder")
                                        .portMappings(PortMapping.builder()
                                                .containerPort(8080)
                                                .hostPort(53234)
                                                .build())
                                        .dockerLabels(ImmutableMap.of(
                                                PROMETHEUS_METRIC_PATH_DOCKER_LABEL, "/metric/path",
                                                PROMETHEUS_PORT_DOCKER_LABEL, "8080"
                                        ))
                                        .build(),
                                ContainerDefinition.builder()
                                        .name("sidecar")
                                        .portMappings(PortMapping.builder()
                                                .containerPort(8081)
                                                .hostPort(53235)
                                                .build())
                                        .build())
                        .build())
                .build());
        metricCollector.recordLatency(eq(SCRAPE_LATENCY_METRIC), anyObject(), anyLong());

        replayAll();
        Optional<StaticConfig> staticConfigOpt = testClass.buildScrapeTarget(scrapeConfig, ecsClient, cluster,
                service, Task.builder()
                        .taskArn("task-arn")
                        .taskDefinitionArn("task-def-arn")
                        .lastStatus("RUNNING")
                        .attachments(Attachment.builder()
                                .type(ENI)
                                .details(KeyValuePair.builder()
                                        .name(PRIVATE_IPv4ADDRESS)
                                        .value("10.20.30.40")
                                        .build())
                                .build())
                        .build());
        assertTrue(staticConfigOpt.isPresent());
        StaticConfig staticConfig = staticConfigOpt.get();
        assertAll(
                () -> assertEquals("cluster", staticConfig.getLabels().getCluster()),
                () -> assertEquals("service", staticConfig.getLabels().getJob()),
                () -> assertEquals("task-def", staticConfig.getLabels().getTaskDefName()),
                () -> assertEquals("5", staticConfig.getLabels().getTaskDefVersion()),
                () -> assertEquals("task-id", staticConfig.getLabels().getTaskId()),
                () -> assertEquals("/metric/path", staticConfig.getLabels().getMetricsPath()),
                () -> assertEquals(ImmutableSet.of("10.20.30.40:53234"), staticConfig.getTargets())
        );
        verifyAll();
    }

    @Test
    public void buildScrapeTarget_use_taskdef_config_success() {
        expect(resourceMapper.map("task-def-arn")).andReturn(Optional.of(taskDef));
        expect(resourceMapper.map("task-arn")).andReturn(Optional.of(task));

        expect(scrapeConfig.getECSScrapeConfig(taskDef)).andReturn(Optional.of(taskDefScrapeConfig));
        expect(taskDefScrapeConfig.getContainerDefinitionName()).andReturn("model-builder");
        expect(taskDefScrapeConfig.getMetricPath()).andReturn("/metric/path").anyTimes();
        expect(taskDefScrapeConfig.getContainerPort()).andReturn(8080).anyTimes();

        expect(ecsClient.describeTaskDefinition(DescribeTaskDefinitionRequest.builder()
                .taskDefinition("task-def-arn")
                .build())).andReturn(DescribeTaskDefinitionResponse.builder()
                .taskDefinition(TaskDefinition.builder()
                        .containerDefinitions(ContainerDefinition.builder()
                                        .name("model-builder")
                                        .portMappings(PortMapping.builder()
                                                .containerPort(8080)
                                                .hostPort(53234)
                                                .build())
                                        .build(),
                                ContainerDefinition.builder()
                                        .name("sidecar")
                                        .portMappings(PortMapping.builder()
                                                .containerPort(8081)
                                                .hostPort(53235)
                                                .build())
                                        .build())
                        .build())
                .build());
        metricCollector.recordLatency(eq(SCRAPE_LATENCY_METRIC), anyObject(), anyLong());

        replayAll();
        Optional<StaticConfig> staticConfigOpt = testClass.buildScrapeTarget(scrapeConfig, ecsClient, cluster,
                service, Task.builder()
                        .taskArn("task-arn")
                        .taskDefinitionArn("task-def-arn")
                        .lastStatus("RUNNING")
                        .attachments(Attachment.builder()
                                .type(ENI)
                                .details(KeyValuePair.builder()
                                        .name(PRIVATE_IPv4ADDRESS)
                                        .value("10.20.30.40")
                                        .build())
                                .build())
                        .build());
        assertTrue(staticConfigOpt.isPresent());
        StaticConfig staticConfig = staticConfigOpt.get();
        assertAll(
                () -> assertEquals("cluster", staticConfig.getLabels().getCluster()),
                () -> assertEquals("service", staticConfig.getLabels().getJob()),
                () -> assertEquals("task-def", staticConfig.getLabels().getTaskDefName()),
                () -> assertEquals("5", staticConfig.getLabels().getTaskDefVersion()),
                () -> assertEquals("task-id", staticConfig.getLabels().getTaskId()),
                () -> assertEquals("/metric/path", staticConfig.getLabels().getMetricsPath()),
                () -> assertEquals(ImmutableSet.of("10.20.30.40:53234"), staticConfig.getTargets())
        );
        verifyAll();
    }

    @Test
    public void buildScrapeTarget_without_taskdef_config_success() {
        expect(resourceMapper.map("task-def-arn")).andReturn(Optional.of(taskDef));
        expect(resourceMapper.map("task-arn")).andReturn(Optional.of(task));

        expect(scrapeConfig.getECSScrapeConfig(taskDef)).andReturn(Optional.empty());
        expect(ecsClient.describeTaskDefinition(DescribeTaskDefinitionRequest.builder()
                .taskDefinition("task-def-arn")
                .build())).andReturn(DescribeTaskDefinitionResponse.builder()
                .taskDefinition(TaskDefinition.builder()
                        .containerDefinitions(ContainerDefinition.builder()
                                .name("model-builder")
                                .portMappings(PortMapping.builder()
                                        .containerPort(8080)
                                        .hostPort(53234)
                                        .build())
                                .build())
                        .build())
                .build());
        metricCollector.recordLatency(eq(SCRAPE_LATENCY_METRIC), anyObject(), anyLong());

        replayAll();
        Optional<StaticConfig> staticConfigOpt = testClass.buildScrapeTarget(scrapeConfig, ecsClient, cluster,
                service, Task.builder()
                        .taskArn("task-arn")
                        .taskDefinitionArn("task-def-arn")
                        .lastStatus("RUNNING")
                        .attachments(Attachment.builder()
                                .type(ENI)
                                .details(KeyValuePair.builder()
                                        .name(PRIVATE_IPv4ADDRESS)
                                        .value("10.20.30.40")
                                        .build())
                                .build())
                        .build());
        assertTrue(staticConfigOpt.isPresent());
        StaticConfig staticConfig = staticConfigOpt.get();
        assertAll(
                () -> assertEquals("cluster", staticConfig.getLabels().getCluster()),
                () -> assertEquals("service", staticConfig.getLabels().getJob()),
                () -> assertEquals("task-id", staticConfig.getLabels().getTaskId()),
                () -> assertEquals("5", staticConfig.getLabels().getTaskDefVersion()),
                () -> assertEquals("/metrics", staticConfig.getLabels().getMetricsPath()),
                () -> assertEquals(ImmutableSet.of("10.20.30.40:53234"), staticConfig.getTargets())
        );
        verifyAll();
    }

    @Test
    public void buildScrapeTarget_without_taskdef_config_no_result() {
        expect(resourceMapper.map("task-def-arn")).andReturn(Optional.of(taskDef));
        expect(resourceMapper.map("task-arn")).andReturn(Optional.of(task));

        expect(scrapeConfig.getECSScrapeConfig(taskDef)).andReturn(Optional.empty());
        expect(ecsClient.describeTaskDefinition(DescribeTaskDefinitionRequest.builder()
                .taskDefinition("task-def-arn")
                .build())).andReturn(DescribeTaskDefinitionResponse.builder()
                .taskDefinition(TaskDefinition.builder()
                        .containerDefinitions(ContainerDefinition.builder()
                                        .name("model-builder")
                                        .portMappings(PortMapping.builder()
                                                .containerPort(8080)
                                                .hostPort(53234)
                                                .build())
                                        .build(),
                                ContainerDefinition.builder()
                                        .name("sidecar")
                                        .portMappings(PortMapping.builder()
                                                .containerPort(8081)
                                                .hostPort(53235)
                                                .build())
                                        .build())
                        .build())
                .build());
        metricCollector.recordLatency(eq(SCRAPE_LATENCY_METRIC), anyObject(), anyLong());

        replayAll();
        Optional<StaticConfig> staticConfigOpt = testClass.buildScrapeTarget(scrapeConfig, ecsClient, cluster,
                service, Task.builder()
                        .taskArn("task-arn")
                        .taskDefinitionArn("task-def-arn")
                        .lastStatus("RUNNING")
                        .attachments(Attachment.builder()
                                .type(ENI)
                                .details(KeyValuePair.builder()
                                        .name(PRIVATE_IPv4ADDRESS)
                                        .value("10.20.30.40")
                                        .build())
                                .build())
                        .build());
        assertFalse(staticConfigOpt.isPresent());
        verifyAll();
    }

    @Test
    public void buildScrapeTarget_use_taskdef_config_exception() {
        expect(resourceMapper.map("task-def-arn")).andReturn(Optional.of(taskDef));
        expect(resourceMapper.map("task-arn")).andReturn(Optional.of(task));

        expect(scrapeConfig.getECSScrapeConfig(taskDef)).andReturn(Optional.of(taskDefScrapeConfig));
        expect(taskDefScrapeConfig.getContainerDefinitionName()).andReturn("model-builder");
        expect(taskDefScrapeConfig.getContainerPort()).andReturn(8080).anyTimes();

        expect(ecsClient.describeTaskDefinition(DescribeTaskDefinitionRequest.builder()
                .taskDefinition("task-def-arn")
                .build())).andThrow(new RuntimeException());
        metricCollector.recordCounterValue(eq(SCRAPE_ERROR_COUNT_METRIC), anyObject(), eq(1));

        replayAll();
        Optional<StaticConfig> staticConfigOpt = testClass.buildScrapeTarget(scrapeConfig, ecsClient, cluster,
                service, Task.builder()
                        .taskArn("task-arn")
                        .taskDefinitionArn("task-def-arn")
                        .lastStatus("RUNNING")
                        .attachments(Attachment.builder()
                                .type(ENI)
                                .details(KeyValuePair.builder()
                                        .name(PRIVATE_IPv4ADDRESS)
                                        .value("10.20.30.40")
                                        .build())
                                .build())
                        .build());
        assertFalse(staticConfigOpt.isPresent());
        verifyAll();
    }
}
