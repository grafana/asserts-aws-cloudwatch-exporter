/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.exporter;

import ai.asserts.aws.AWSApiCallRateLimiter;
import ai.asserts.aws.AWSClientProvider;
import ai.asserts.aws.ScrapeConfigProvider;
import ai.asserts.aws.TagUtil;
import ai.asserts.aws.TaskExecutorUtil;
import ai.asserts.aws.TestTaskThreadPool;
import ai.asserts.aws.account.AWSAccount;
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
import software.amazon.awssdk.services.ecs.model.LogConfiguration;
import software.amazon.awssdk.services.ecs.model.LogDriver;
import software.amazon.awssdk.services.ecs.model.Task;
import software.amazon.awssdk.services.ecs.model.TaskDefinition;

import java.util.List;
import java.util.Optional;

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

@SuppressWarnings("unchecked")
public class ECSTaskUtilTest extends EasyMockSupport {
    private ResourceMapper resourceMapper;
    private BasicMetricCollector metricCollector;
    private EcsClient ecsClient;
    private ECSTaskUtil testClass;
    private Resource cluster;
    private Resource service;
    private Resource task;
    private Resource taskDef;
    private TagUtil tagUtil;
    private ScrapeConfig scrapeConfig;
    private AWSAccount account;
    private String defaultEnvName;
    private AWSClientProvider awsClientProvider;
    private Ec2Client ec2Client;
    private ScrapeConfigProvider scrapeConfigProvider;

    @BeforeEach
    public void setup() {
        account = AWSAccount.builder()
                .accountId("account")
                .tenant("acme")
                .build();
        resourceMapper = mock(ResourceMapper.class);
        metricCollector = mock(BasicMetricCollector.class);
        ecsClient = mock(EcsClient.class);
        scrapeConfigProvider = mock(ScrapeConfigProvider.class);
        awsClientProvider = mock(AWSClientProvider.class);
        tagUtil = mock(TagUtil.class);
        scrapeConfig = mock(ScrapeConfig.class);
        ec2Client = mock(Ec2Client.class);


        AWSApiCallRateLimiter rateLimiter = new AWSApiCallRateLimiter(metricCollector, (account) -> "acme");
        TaskExecutorUtil taskExecutorUtil = new TaskExecutorUtil(new TestTaskThreadPool(),
                rateLimiter) {
            @Override
            public AWSAccount getAccountDetails() {
                return account;
            }
        };


        defaultEnvName = "dev";
        testClass = new ECSTaskUtil(awsClientProvider, resourceMapper,
                rateLimiter, tagUtil,
                taskExecutorUtil, scrapeConfigProvider) {
            @Override
            String getInstallEnvName() {
                return defaultEnvName;
            }
        };

        cluster = Resource.builder()
                .tenant("acme")
                .name("cluster")
                .region("us-west-2")
                .account("account")
                .build();
        service = Resource.builder()
                .tenant("acme")
                .name("service")
                .region("us-west-2")
                .account("account")
                .build();
        task = Resource.builder()
                .tenant("acme")
                .name("task-id")
                .region("us-west-2")
                .account("account")
                .build();
        taskDef = Resource.builder()
                .tenant("acme")
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
    public void getEnvName() {
        assertEquals("prod", testClass.getEnv(AWSAccount.builder()
                .name("prod")
                .build()));
        assertEquals(defaultEnvName, testClass.getEnv(AWSAccount.builder()
                .build()));
    }

    @Test
    public void containerWithDockerLabels() {
        expect(awsClientProvider.getEc2Client(anyString(), anyObject())).andReturn(ec2Client).anyTimes();
        expect(ec2Client.describeSubnets(DescribeSubnetsRequest.builder()
                .subnetIds("subnet-id")
                .build())).andReturn(DescribeSubnetsResponse.builder()
                .subnets(Subnet.builder()
                        .vpcId("vpc-id")
                        .build())
                .build()).anyTimes();

        expect(scrapeConfigProvider.getScrapeConfig("acme")).andReturn(scrapeConfig);
        expect(scrapeConfig.isFetchEC2Metadata()).andReturn(true);

        expect(resourceMapper.map("task-def-arn")).andReturn(Optional.of(taskDef));
        expect(resourceMapper.map("task-arn")).andReturn(Optional.of(task));

        ImmutableMap<String, String> logDriverOptions = ImmutableMap.of(
                "awslogs-group", "asserts-aws-integration-Dev",
                "awslogs-region", "us-west-2",
                "awslogs-stream-prefix", "cloudwatch-exporter"
        );
        TaskDefinition taskDefinition = TaskDefinition.builder()
                .containerDefinitions(ContainerDefinition.builder()
                        .name("model-builder")
                        .image("image")
                        .dockerLabels(ImmutableMap.of(
                                PROMETHEUS_METRIC_PATH_DOCKER_LABEL, "/metric/path",
                                PROMETHEUS_PORT_DOCKER_LABEL, "8080"
                        ))
                        .logConfiguration(LogConfiguration.builder()
                                .logDriver(LogDriver.AWSLOGS)
                                .options(logDriverOptions)
                                .build())
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

        expect(tagUtil.tagLabels(eq(scrapeConfig), anyObject(List.class))).andReturn(ImmutableMap.of("tag_key",
                "tag_value"));

        replayAll();
        List<StaticConfig> staticConfigs = testClass.buildScrapeTargets(account, scrapeConfig, ecsClient, cluster,
                Optional.of(service.getName()), Task.builder()
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
                () -> assertEquals(ImmutableSet.of("10.20.30.40:8080"), staticConfig.getTargets()),
                () -> assertEquals(ImmutableSet.of(ECSServiceDiscoveryExporter.LogConfig.builder()
                                .logDriver(LogDriver.AWSLOGS.toString())
                                .options(logDriverOptions)
                                .build()),
                        staticConfig.getLogConfigs())
        );
        verifyAll();
    }

    @Test
    public void containerWithDockerLabels_SkipVPCDiscovery() {
        expect(scrapeConfigProvider.getScrapeConfig("acme")).andReturn(scrapeConfig);
        expect(scrapeConfig.isFetchEC2Metadata()).andReturn(false);

        expect(resourceMapper.map("task-def-arn")).andReturn(Optional.of(taskDef));
        expect(resourceMapper.map("task-arn")).andReturn(Optional.of(task));

        ImmutableMap<String, String> logDriverOptions = ImmutableMap.of(
                "awslogs-group", "asserts-aws-integration-Dev",
                "awslogs-region", "us-west-2",
                "awslogs-stream-prefix", "cloudwatch-exporter"
        );
        TaskDefinition taskDefinition = TaskDefinition.builder()
                .containerDefinitions(ContainerDefinition.builder()
                        .name("model-builder")
                        .image("image")
                        .dockerLabels(ImmutableMap.of(
                                PROMETHEUS_METRIC_PATH_DOCKER_LABEL, "/metric/path",
                                PROMETHEUS_PORT_DOCKER_LABEL, "8080"
                        ))
                        .logConfiguration(LogConfiguration.builder()
                                .logDriver(LogDriver.AWSLOGS)
                                .options(logDriverOptions)
                                .build())
                        .build())
                .build();

        expect(ecsClient.describeTaskDefinition(DescribeTaskDefinitionRequest.builder()
                .taskDefinition("task-def-arn")
                .build())).andReturn(DescribeTaskDefinitionResponse.builder()
                .taskDefinition(taskDefinition)
                .build());
        metricCollector.recordLatency(eq(SCRAPE_LATENCY_METRIC), anyObject(), anyLong());

        expect(tagUtil.tagLabels(eq(scrapeConfig), anyObject(List.class))).andReturn(ImmutableMap.of("tag_key",
                "tag_value"));

        replayAll();
        List<StaticConfig> staticConfigs = testClass.buildScrapeTargets(account, scrapeConfig, ecsClient, cluster,
                Optional.of(service.getName()), Task.builder()
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
                () -> assertEquals("", staticConfig.getLabels().getVpcId()),
                () -> assertEquals(ImmutableSet.of("10.20.30.40:8080"), staticConfig.getTargets()),
                () -> assertEquals(ImmutableSet.of(ECSServiceDiscoveryExporter.LogConfig.builder()
                                .logDriver(LogDriver.AWSLOGS.toString())
                                .options(logDriverOptions)
                                .build()),
                        staticConfig.getLogConfigs())
        );
        verifyAll();
    }

    @Test
    public void containerWithoutDockerLabels() {
        expect(awsClientProvider.getEc2Client(anyString(), anyObject())).andReturn(ec2Client).anyTimes();
        expect(ec2Client.describeSubnets(DescribeSubnetsRequest.builder()
                .subnetIds("subnet-id")
                .build())).andReturn(DescribeSubnetsResponse.builder()
                .subnets(Subnet.builder()
                        .vpcId("vpc-id")
                        .build())
                .build()).anyTimes();

        expect(scrapeConfigProvider.getScrapeConfig("acme")).andReturn(scrapeConfig);
        expect(scrapeConfig.isFetchEC2Metadata()).andReturn(true);

        expect(resourceMapper.map("task-def-arn")).andReturn(Optional.of(taskDef));
        expect(resourceMapper.map("task-arn")).andReturn(Optional.of(task));

        TaskDefinition taskDefinition = TaskDefinition.builder()
                .containerDefinitions(ContainerDefinition.builder()
                        .name("model-builder")
                        .image("image")
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

        expect(tagUtil.tagLabels(eq(scrapeConfig), anyObject(List.class))).andReturn(ImmutableMap.of("tag_key",
                "tag_value"));

        replayAll();
        List<StaticConfig> staticConfigs = testClass.buildScrapeTargets(account, scrapeConfig, ecsClient, cluster,
                Optional.of(service.getName()), Task.builder()
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
        assertEquals(0, staticConfigs.size());
        verifyAll();
    }
}
