/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.exporter;

import ai.asserts.aws.AWSClientProvider;
import ai.asserts.aws.RateLimiter;
import ai.asserts.aws.config.ECSTaskDefScrapeConfig;
import ai.asserts.aws.config.ScrapeConfig;
import ai.asserts.aws.exporter.ECSServiceDiscoveryExporter.StaticConfig;
import ai.asserts.aws.resource.Resource;
import ai.asserts.aws.resource.ResourceMapper;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import org.easymock.EasyMockSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.DescribeSubnetsRequest;
import software.amazon.awssdk.services.ec2.model.DescribeSubnetsResponse;
import software.amazon.awssdk.services.ec2.model.Subnet;
import software.amazon.awssdk.services.ecs.EcsClient;
import software.amazon.awssdk.services.ecs.model.Attachment;
import software.amazon.awssdk.services.ecs.model.ContainerDefinition;
import software.amazon.awssdk.services.ecs.model.DescribeTaskDefinitionRequest;
import software.amazon.awssdk.services.ecs.model.DescribeTaskDefinitionResponse;
import software.amazon.awssdk.services.ecs.model.KeyValuePair;
import software.amazon.awssdk.services.ecs.model.PortMapping;
import software.amazon.awssdk.services.ecs.model.Task;
import software.amazon.awssdk.services.ecs.model.TaskDefinition;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import static ai.asserts.aws.MetricNameUtil.SCRAPE_LATENCY_METRIC;
import static ai.asserts.aws.exporter.ECSTaskUtil.ENI;
import static ai.asserts.aws.exporter.ECSTaskUtil.PRIVATE_IPv4ADDRESS;
import static ai.asserts.aws.exporter.ECSTaskUtil.PROMETHEUS_METRIC_PATH_DOCKER_LABEL;
import static ai.asserts.aws.exporter.ECSTaskUtil.PROMETHEUS_PORT_DOCKER_LABEL;
import static ai.asserts.aws.exporter.ECSTaskUtil.SUBNET_ID;
import static org.easymock.EasyMock.anyLong;
import static org.easymock.EasyMock.anyObject;
import static org.easymock.EasyMock.anyString;
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
        AWSClientProvider awsClientProvider = mock(AWSClientProvider.class);
        Ec2Client ec2Client = mock(Ec2Client.class);
        testClass = new ECSTaskUtil(awsClientProvider, resourceMapper, new RateLimiter(metricCollector));

        expect(awsClientProvider.getEc2Client(anyString(), anyObject())).andReturn(ec2Client).anyTimes();
        expect(ec2Client.describeSubnets(DescribeSubnetsRequest.builder()
                .subnetIds("subnet-id")
                .build())).andReturn(DescribeSubnetsResponse.builder()
                .subnets(Subnet.builder()
                        .vpcId("vpc-id")
                        .build())
                .build()).anyTimes();

        cluster = Resource.builder()
                .name("cluster")
                .region("us-west-2")
                .account("account")
                .build();
        service = Resource.builder()
                .name("service")
                .region("us-west-2")
                .account("account")
                .build();
        task = Resource.builder()
                .name("task-id")
                .region("us-west-2")
                .account("account")
                .build();
        taskDef = Resource.builder()
                .name("task-def")
                .version("5")
                .account("account")
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
    public void containerWithDockerLabels() {
        expect(resourceMapper.map("task-def-arn")).andReturn(Optional.of(taskDef));
        expect(resourceMapper.map("task-arn")).andReturn(Optional.of(task));
        expect(scrapeConfig.getECSConfigByNameAndPort()).andReturn(ImmutableMap.of());

        TaskDefinition taskDefinition = TaskDefinition.builder()
                .containerDefinitions(ContainerDefinition.builder()
                        .name("model-builder")
                        .image("image")
                        .dockerLabels(ImmutableMap.of(
                                PROMETHEUS_METRIC_PATH_DOCKER_LABEL, "/metric/path",
                                PROMETHEUS_PORT_DOCKER_LABEL, "8080"
                        ))
                        .build())
                .build();

        // For Describe Subnets call
        metricCollector.recordLatency(eq(SCRAPE_LATENCY_METRIC), anyObject(), anyLong());

        expect(ecsClient.describeTaskDefinition(DescribeTaskDefinitionRequest.builder()
                .taskDefinition("task-def-arn")
                .build())).andReturn(DescribeTaskDefinitionResponse.builder()
                .taskDefinition(taskDefinition)
                .build());
        metricCollector.recordLatency(eq(SCRAPE_LATENCY_METRIC), anyObject(), anyLong());
        expect(scrapeConfig.additionalLabels(eq("up"), anyObject())).andReturn(ImmutableMap.of()).anyTimes();
        replayAll();
        List<StaticConfig> staticConfigs = testClass.buildScrapeTargets(scrapeConfig, ecsClient, cluster,
                Optional.of(service), Task.builder()
                        .taskArn("task-arn")
                        .taskDefinitionArn("task-def-arn")
                        .lastStatus("RUNNING")
                        .attachments(Attachment.builder()
                                .type(ENI)
                                .details(KeyValuePair.builder()
                                                .name(PRIVATE_IPv4ADDRESS)
                                                .value("10.20.30.40")
                                                .build(),
                                        KeyValuePair.builder()
                                                .name(SUBNET_ID)
                                                .value("subnet-id")
                                                .build()
                                )
                                .build())
                        .build());
        assertEquals(1, staticConfigs.size());
        StaticConfig staticConfig = staticConfigs.get(0);
        assertAll(
                () -> assertEquals("cluster", staticConfig.getLabels().getCluster()),
                () -> assertEquals("model-builder", staticConfig.getLabels().getJob()),
                () -> assertEquals("task-def", staticConfig.getLabels().getTaskDefName()),
                () -> assertEquals("5", staticConfig.getLabels().getTaskDefVersion()),
                () -> assertEquals("service-task-id", staticConfig.getLabels().getPod()),
                () -> assertEquals("/metric/path", staticConfig.getLabels().getMetricsPath()),
                () -> assertEquals("model-builder", staticConfig.getLabels().getContainer()),
                () -> assertEquals("vpc-id", staticConfig.getLabels().getVpcId()),
                () -> assertEquals(ImmutableSet.of("10.20.30.40:8080"), staticConfig.getTargets())
        );
        verifyAll();
    }

    @Test
    public void discoverAllTasksNoConfig() {
        expect(resourceMapper.map("task-def-arn")).andReturn(Optional.of(taskDef));
        expect(resourceMapper.map("task-arn")).andReturn(Optional.of(task));
        expect(scrapeConfig.isDiscoverAllECSTasksByDefault()).andReturn(true).anyTimes();
        expect(scrapeConfig.getECSConfigByNameAndPort()).andReturn(ImmutableMap.of());

        expect(taskDefScrapeConfig.getMetricPath()).andReturn("/prometheus/metrics").anyTimes();

        TaskDefinition taskDefinition = TaskDefinition.builder()
                .containerDefinitions(
                        ContainerDefinition.builder()
                                .name("model-builder")
                                .image("image")
                                .portMappings(PortMapping.builder()
                                        .hostPort(52341)
                                        .containerPort(8080)
                                        .build())
                                .build(),
                        ContainerDefinition.builder()
                                .name("api-server")
                                .image("image")
                                .portMappings(
                                        PortMapping.builder()
                                                .hostPort(52342)
                                                .containerPort(8081)
                                                .build(),
                                        PortMapping.builder()
                                                .hostPort(52343)
                                                .containerPort(8082)
                                                .build()
                                ).build()
                ).build();

        metricCollector.recordLatency(eq(SCRAPE_LATENCY_METRIC), anyObject(), anyLong());

        expect(ecsClient.describeTaskDefinition(DescribeTaskDefinitionRequest.builder()
                .taskDefinition("task-def-arn")
                .build())).andReturn(DescribeTaskDefinitionResponse.builder()
                .taskDefinition(taskDefinition)
                .build());
        metricCollector.recordLatency(eq(SCRAPE_LATENCY_METRIC), anyObject(), anyLong());
        expect(scrapeConfig.additionalLabels(eq("up"), anyObject())).andReturn(ImmutableMap.of()).anyTimes();
        replayAll();
        List<StaticConfig> staticConfigs = testClass.buildScrapeTargets(scrapeConfig, ecsClient, cluster,
                Optional.of(service), Task.builder()
                        .taskArn("task-arn")
                        .taskDefinitionArn("task-def-arn")
                        .lastStatus("RUNNING")
                        .attachments(Attachment.builder()
                                .type(ENI)
                                .details(KeyValuePair.builder()
                                                .name(PRIVATE_IPv4ADDRESS)
                                                .value("10.20.30.40")
                                                .build(),
                                        KeyValuePair.builder()
                                                .name(SUBNET_ID)
                                                .value("subnet-id")
                                                .build())
                                .build())
                        .build());
        assertEquals(2, staticConfigs.size());
        assertAll(
                () -> assertEquals("cluster", staticConfigs.get(0).getLabels().getCluster()),
                () -> assertEquals("task-def", staticConfigs.get(0).getLabels().getTaskDefName()),
                () -> assertEquals("5", staticConfigs.get(0).getLabels().getTaskDefVersion()),
                () -> assertEquals("service-task-id", staticConfigs.get(0).getLabels().getPod()),
                () -> assertEquals("/metrics", staticConfigs.get(0).getLabels().getMetricsPath()),
                () -> assertEquals("vpc-id", staticConfigs.get(0).getLabels().getVpcId()),

                () -> assertEquals("cluster", staticConfigs.get(1).getLabels().getCluster()),
                () -> assertEquals("task-def", staticConfigs.get(1).getLabels().getTaskDefName()),
                () -> assertEquals("5", staticConfigs.get(1).getLabels().getTaskDefVersion()),
                () -> assertEquals("service-task-id", staticConfigs.get(1).getLabels().getPod()),
                () -> assertEquals("/metrics", staticConfigs.get(1).getLabels().getMetricsPath()),
                () -> assertEquals("vpc-id", staticConfigs.get(0).getLabels().getVpcId()),
                () -> assertEquals(ImmutableSet.of("api-server", "model-builder"), staticConfigs.stream()
                        .map(sc -> sc.getLabels().getJob())
                        .collect(Collectors.toSet())),
                () -> assertEquals(ImmutableSet.of("api-server", "model-builder"), staticConfigs.stream()
                        .map(sc -> sc.getLabels().getContainer())
                        .collect(Collectors.toSet())),
                () -> assertEquals(ImmutableSet.of("10.20.30.40:52342", "10.20.30.40:52343"),
                        staticConfigs.stream()
                                .filter(sc -> sc.getLabels().getContainer().equals("api-server"))
                                .map(StaticConfig::getTargets)
                                .findFirst().get()),
                () -> assertEquals(ImmutableSet.of("10.20.30.40:52341"),
                        staticConfigs.stream()
                                .filter(sc -> sc.getLabels().getContainer().equals("model-builder"))
                                .map(StaticConfig::getTargets)
                                .findFirst().get())
        );
        verifyAll();
    }

    @Test
    public void discoverAllTasksSomeWithConfig() {
        expect(resourceMapper.map("task-def-arn")).andReturn(Optional.of(taskDef));
        expect(resourceMapper.map("task-arn")).andReturn(Optional.of(task));
        expect(scrapeConfig.isDiscoverAllECSTasksByDefault()).andReturn(true).anyTimes();
        expect(scrapeConfig.getECSConfigByNameAndPort()).andReturn(
                ImmutableMap.of("api-server",
                        ImmutableMap.of(-1, taskDefScrapeConfig)));

        expect(taskDefScrapeConfig.getMetricPath()).andReturn("/prometheus/metrics").anyTimes();

        TaskDefinition taskDefinition = TaskDefinition.builder()
                .containerDefinitions(
                        ContainerDefinition.builder()
                                .name("model-builder")
                                .image("image")
                                .portMappings(PortMapping.builder()
                                        .hostPort(52341)
                                        .containerPort(8080)
                                        .build())
                                .build(),
                        ContainerDefinition.builder()
                                .name("api-server")
                                .image("image")
                                .portMappings(
                                        PortMapping.builder()
                                                .hostPort(52342)
                                                .containerPort(8081)
                                                .build(),
                                        PortMapping.builder()
                                                .hostPort(52343)
                                                .containerPort(8082)
                                                .build()
                                ).build()
                ).build();

        metricCollector.recordLatency(eq(SCRAPE_LATENCY_METRIC), anyObject(), anyLong());

        expect(ecsClient.describeTaskDefinition(DescribeTaskDefinitionRequest.builder()
                .taskDefinition("task-def-arn")
                .build())).andReturn(DescribeTaskDefinitionResponse.builder()
                .taskDefinition(taskDefinition)
                .build());
        metricCollector.recordLatency(eq(SCRAPE_LATENCY_METRIC), anyObject(), anyLong());
        expect(scrapeConfig.additionalLabels(eq("up"), anyObject())).andReturn(ImmutableMap.of()).anyTimes();
        replayAll();
        List<StaticConfig> staticConfigs = testClass.buildScrapeTargets(scrapeConfig, ecsClient, cluster,
                Optional.of(service), Task.builder()
                        .taskArn("task-arn")
                        .taskDefinitionArn("task-def-arn")
                        .lastStatus("RUNNING")
                        .attachments(Attachment.builder()
                                .type(ENI)
                                .details(KeyValuePair.builder()
                                                .name(PRIVATE_IPv4ADDRESS)
                                                .value("10.20.30.40")
                                                .build(),
                                        KeyValuePair.builder()
                                                .name(SUBNET_ID)
                                                .value("subnet-id")
                                                .build()
                                )
                                .build())
                        .build());
        assertEquals(2, staticConfigs.size());
        assertAll(
                () -> assertEquals("cluster", staticConfigs.get(0).getLabels().getCluster()),
                () -> assertEquals("task-def", staticConfigs.get(0).getLabels().getTaskDefName()),
                () -> assertEquals("5", staticConfigs.get(0).getLabels().getTaskDefVersion()),
                () -> assertEquals("service-task-id", staticConfigs.get(0).getLabels().getPod()),
                () -> assertEquals("vpc-id", staticConfigs.get(0).getLabels().getVpcId()),

                () -> assertEquals("cluster", staticConfigs.get(1).getLabels().getCluster()),
                () -> assertEquals("task-def", staticConfigs.get(1).getLabels().getTaskDefName()),
                () -> assertEquals("5", staticConfigs.get(1).getLabels().getTaskDefVersion()),
                () -> assertEquals("service-task-id", staticConfigs.get(1).getLabels().getPod()),
                () -> assertEquals("vpc-id", staticConfigs.get(0).getLabels().getVpcId()),
                () -> assertEquals(ImmutableSet.of("api-server", "model-builder"), staticConfigs.stream()
                        .map(sc -> sc.getLabels().getJob())
                        .collect(Collectors.toSet())),
                () -> assertEquals(ImmutableSet.of("api-server", "model-builder"), staticConfigs.stream()
                        .map(sc -> sc.getLabels().getContainer())
                        .collect(Collectors.toSet())),
                () -> assertEquals("/prometheus/metrics",
                        staticConfigs.stream()
                                .filter(sc -> sc.getLabels().getContainer().equals("api-server"))
                                .map(sc -> sc.getLabels().getMetricsPath())
                                .findFirst().get()),
                () -> assertEquals("/metrics",
                        staticConfigs.stream()
                                .filter(sc -> sc.getLabels().getContainer().equals("model-builder"))
                                .map(sc -> sc.getLabels().getMetricsPath())
                                .findFirst().get()),
                () -> assertEquals(ImmutableSet.of("10.20.30.40:52342", "10.20.30.40:52343"),
                        staticConfigs.stream()
                                .filter(sc -> sc.getLabels().getContainer().equals("api-server"))
                                .map(StaticConfig::getTargets)
                                .findFirst().get()),
                () -> assertEquals(ImmutableSet.of("10.20.30.40:52341"),
                        staticConfigs.stream()
                                .filter(sc -> sc.getLabels().getContainer().equals("model-builder"))
                                .map(StaticConfig::getTargets)
                                .findFirst().get())
        );
        verifyAll();
    }

    @Test
    public void discoverAllTasksSpecificConfigForSpecificPorts() {
        expect(resourceMapper.map("task-def-arn")).andReturn(Optional.of(taskDef));
        expect(resourceMapper.map("task-arn")).andReturn(Optional.of(task));
        expect(scrapeConfig.isDiscoverAllECSTasksByDefault()).andReturn(true).anyTimes();
        expect(scrapeConfig.getECSConfigByNameAndPort()).andReturn(
                ImmutableMap.of("api-server",
                        ImmutableMap.of(8081, taskDefScrapeConfig)));

        expect(taskDefScrapeConfig.getMetricPath()).andReturn("/prometheus/metrics").anyTimes();

        TaskDefinition taskDefinition = TaskDefinition.builder()
                .containerDefinitions(
                        ContainerDefinition.builder()
                                .name("model-builder")
                                .image("image")
                                .portMappings(PortMapping.builder()
                                        .hostPort(52341)
                                        .containerPort(8080)
                                        .build())
                                .build(),
                        ContainerDefinition.builder()
                                .image("image")
                                .name("api-server")
                                .portMappings(
                                        PortMapping.builder()
                                                .hostPort(52342)
                                                .containerPort(8081)
                                                .build(),
                                        PortMapping.builder()
                                                .hostPort(52343)
                                                .containerPort(8082)
                                                .build()
                                ).build()
                ).build();

        metricCollector.recordLatency(eq(SCRAPE_LATENCY_METRIC), anyObject(), anyLong());

        expect(ecsClient.describeTaskDefinition(DescribeTaskDefinitionRequest.builder()
                .taskDefinition("task-def-arn")
                .build())).andReturn(DescribeTaskDefinitionResponse.builder()
                .taskDefinition(taskDefinition)
                .build());
        metricCollector.recordLatency(eq(SCRAPE_LATENCY_METRIC), anyObject(), anyLong());

        expect(scrapeConfig.additionalLabels(eq("up"), anyObject())).andReturn(ImmutableMap.of()).anyTimes();
        replayAll();
        List<StaticConfig> staticConfigs = testClass.buildScrapeTargets(scrapeConfig, ecsClient, cluster,
                Optional.of(service), Task.builder()
                        .taskArn("task-arn")
                        .taskDefinitionArn("task-def-arn")
                        .lastStatus("RUNNING")
                        .attachments(Attachment.builder()
                                .type(ENI)
                                .details(KeyValuePair.builder()
                                                .name(PRIVATE_IPv4ADDRESS)
                                                .value("10.20.30.40")
                                                .build(),
                                        KeyValuePair.builder()
                                                .name(SUBNET_ID)
                                                .value("subnet-id")
                                                .build()
                                )
                                .build())
                        .build());
        assertEquals(3, staticConfigs.size());

        Optional<StaticConfig> apiServer_8081 = staticConfigs.stream()
                .filter(sc -> sc.getLabels().getContainer().equals("api-server"))
                .filter(sc -> sc.getTargets().contains("10.20.30.40:52342"))
                .findFirst();

        Optional<StaticConfig> apiServer_8082 = staticConfigs.stream()
                .filter(sc -> sc.getLabels().getContainer().equals("api-server"))
                .filter(sc -> sc.getTargets().contains("10.20.30.40:52343"))
                .findFirst();

        Optional<StaticConfig> modelBuilder = staticConfigs.stream()
                .filter(sc -> sc.getLabels().getContainer().equals("model-builder"))
                .filter(sc -> sc.getTargets().contains("10.20.30.40:52341"))
                .findFirst();

        assertAll(
                () -> assertTrue(apiServer_8081.isPresent()),
                () -> assertEquals("cluster", apiServer_8081.get().getLabels().getCluster()),
                () -> assertEquals("api-server", apiServer_8081.get().getLabels().getJob()),
                () -> assertEquals("task-def", apiServer_8081.get().getLabels().getTaskDefName()),
                () -> assertEquals("5", apiServer_8081.get().getLabels().getTaskDefVersion()),
                () -> assertEquals("service-task-id", apiServer_8081.get().getLabels().getPod()),
                () -> assertEquals("/prometheus/metrics", apiServer_8081.get().getLabels().getMetricsPath())
        );
        assertAll(
                () -> assertTrue(apiServer_8082.isPresent()),
                () -> assertEquals("cluster", apiServer_8082.get().getLabels().getCluster()),
                () -> assertEquals("api-server", apiServer_8082.get().getLabels().getJob()),
                () -> assertEquals("task-def", apiServer_8082.get().getLabels().getTaskDefName()),
                () -> assertEquals("5", apiServer_8082.get().getLabels().getTaskDefVersion()),
                () -> assertEquals("service-task-id", apiServer_8082.get().getLabels().getPod()),
                () -> assertEquals("/metrics", apiServer_8082.get().getLabels().getMetricsPath())
        );
        assertAll(
                () -> assertTrue(modelBuilder.isPresent()),
                () -> assertEquals("cluster", modelBuilder.get().getLabels().getCluster()),
                () -> assertEquals("model-builder", modelBuilder.get().getLabels().getJob()),
                () -> assertEquals("task-def", modelBuilder.get().getLabels().getTaskDefName()),
                () -> assertEquals("5", modelBuilder.get().getLabels().getTaskDefVersion()),
                () -> assertEquals("service-task-id", modelBuilder.get().getLabels().getPod()),
                () -> assertEquals("/metrics", modelBuilder.get().getLabels().getMetricsPath())
        );
        verifyAll();
    }

    @Test
    public void discoverOnlyConfiguredTasks() {
        expect(resourceMapper.map("task-def-arn")).andReturn(Optional.of(taskDef));
        expect(resourceMapper.map("task-arn")).andReturn(Optional.of(task));
        expect(scrapeConfig.isDiscoverAllECSTasksByDefault()).andReturn(false).anyTimes();
        expect(scrapeConfig.getECSConfigByNameAndPort()).andReturn(
                ImmutableMap.of("api-server", ImmutableMap.of(8081, taskDefScrapeConfig)));

        expect(taskDefScrapeConfig.getMetricPath()).andReturn("/prometheus/metrics").anyTimes();

        TaskDefinition taskDefinition = TaskDefinition.builder()
                .containerDefinitions(
                        ContainerDefinition.builder()
                                .name("model-builder")
                                .image("image")
                                .portMappings(PortMapping.builder()
                                        .hostPort(52341)
                                        .containerPort(8080)
                                        .build())
                                .build(),
                        ContainerDefinition.builder()
                                .name("api-server")
                                .image("image")
                                .portMappings(
                                        PortMapping.builder()
                                                .hostPort(52342)
                                                .containerPort(8081)
                                                .build(),
                                        PortMapping.builder()
                                                .hostPort(52343)
                                                .containerPort(8082)
                                                .build()
                                ).build()
                ).build();

        metricCollector.recordLatency(eq(SCRAPE_LATENCY_METRIC), anyObject(), anyLong());

        expect(ecsClient.describeTaskDefinition(DescribeTaskDefinitionRequest.builder()
                .taskDefinition("task-def-arn")
                .build())).andReturn(DescribeTaskDefinitionResponse.builder()
                .taskDefinition(taskDefinition)
                .build());
        metricCollector.recordLatency(eq(SCRAPE_LATENCY_METRIC), anyObject(), anyLong());
        expect(scrapeConfig.additionalLabels(eq("up"), anyObject())).andReturn(ImmutableMap.of()).anyTimes();
        replayAll();
        List<StaticConfig> staticConfigs = testClass.buildScrapeTargets(scrapeConfig, ecsClient, cluster,
                Optional.of(service), Task.builder()
                        .taskArn("task-arn")
                        .taskDefinitionArn("task-def-arn")
                        .lastStatus("RUNNING")
                        .attachments(Attachment.builder()
                                .type(ENI)
                                .details(KeyValuePair.builder()
                                                .name(PRIVATE_IPv4ADDRESS)
                                                .value("10.20.30.40")
                                                .build(),
                                        KeyValuePair.builder()
                                                .name(SUBNET_ID)
                                                .value("subnet-id")
                                                .build()
                                )
                                .build())
                        .build());
        assertEquals(1, staticConfigs.size());
        assertAll(
                () -> assertEquals("cluster", staticConfigs.get(0).getLabels().getCluster()),
                () -> assertEquals("api-server", staticConfigs.get(0).getLabels().getJob()),
                () -> assertEquals("task-def", staticConfigs.get(0).getLabels().getTaskDefName()),
                () -> assertEquals("5", staticConfigs.get(0).getLabels().getTaskDefVersion()),
                () -> assertEquals("service-task-id", staticConfigs.get(0).getLabels().getPod()),
                () -> assertEquals("/prometheus/metrics", staticConfigs.get(0).getLabels().getMetricsPath()),
                () -> assertEquals("api-server", staticConfigs.get(0).getLabels().getContainer()),
                () -> assertEquals(ImmutableSet.of("10.20.30.40:52342"),
                        staticConfigs.get(0).getTargets())
        );
        verifyAll();
    }

}
