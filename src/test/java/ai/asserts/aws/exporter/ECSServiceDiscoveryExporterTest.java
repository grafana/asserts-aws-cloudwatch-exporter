/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.exporter;

import ai.asserts.aws.AWSClientProvider;
import ai.asserts.aws.ObjectMapperFactory;
import ai.asserts.aws.RateLimiter;
import ai.asserts.aws.cloudwatch.config.ScrapeConfig;
import ai.asserts.aws.cloudwatch.config.ScrapeConfigProvider;
import ai.asserts.aws.exporter.ECSServiceDiscoveryExporter.StaticConfig;
import ai.asserts.aws.resource.Resource;
import ai.asserts.aws.resource.ResourceMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import org.easymock.EasyMockSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.ecs.EcsClient;
import software.amazon.awssdk.services.ecs.model.DescribeTasksRequest;
import software.amazon.awssdk.services.ecs.model.DescribeTasksResponse;
import software.amazon.awssdk.services.ecs.model.ListClustersResponse;
import software.amazon.awssdk.services.ecs.model.ListServicesRequest;
import software.amazon.awssdk.services.ecs.model.ListServicesResponse;
import software.amazon.awssdk.services.ecs.model.ListTasksRequest;
import software.amazon.awssdk.services.ecs.model.ListTasksResponse;
import software.amazon.awssdk.services.ecs.model.Task;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static ai.asserts.aws.MetricNameUtil.SCRAPE_ERROR_COUNT_METRIC;
import static ai.asserts.aws.MetricNameUtil.SCRAPE_LATENCY_METRIC;
import static ai.asserts.aws.resource.ResourceType.ECSCluster;
import static ai.asserts.aws.resource.ResourceType.ECSService;
import static org.easymock.EasyMock.anyLong;
import static org.easymock.EasyMock.anyObject;
import static org.easymock.EasyMock.eq;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.expectLastCall;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class ECSServiceDiscoveryExporterTest extends EasyMockSupport {
    private ScrapeConfigProvider scrapeConfigProvider;
    private ScrapeConfig scrapeConfig;
    private AWSClientProvider awsClientProvider;
    private EcsClient ecsClient;
    private ResourceMapper resourceMapper;
    private Resource resource;
    private ECSTaskUtil ecsTaskUtil;
    private BasicMetricCollector metricCollector;
    private ObjectMapperFactory objectMapperFactory;
    private RateLimiter rateLimiter;
    private ObjectMapper objectMapper;
    private ObjectWriter objectWriter;
    private StaticConfig mockStaticConfig;

    @BeforeEach
    public void setup() {
        scrapeConfigProvider = mock(ScrapeConfigProvider.class);
        scrapeConfig = mock(ScrapeConfig.class);
        awsClientProvider = mock(AWSClientProvider.class);
        ecsClient = mock(EcsClient.class);
        resourceMapper = mock(ResourceMapper.class);
        resource = mock(Resource.class);
        ecsTaskUtil = mock(ECSTaskUtil.class);
        metricCollector = mock(BasicMetricCollector.class);
        objectMapperFactory = mock(ObjectMapperFactory.class);
        objectMapper = mock(ObjectMapper.class);
        objectWriter = mock(ObjectWriter.class);
        mockStaticConfig = mock(StaticConfig.class);

        rateLimiter = new RateLimiter();
        resetAll();
    }

    @Test
    public void run() throws Exception {
        expect(scrapeConfigProvider.getScrapeConfig()).andReturn(scrapeConfig);
        expect(scrapeConfig.isECSMonitoringOn()).andReturn(true);
        expect(scrapeConfig.getRegions()).andReturn(ImmutableSet.of("region1", "region2"));

        expect(awsClientProvider.getECSClient("region1")).andReturn(ecsClient);
        expect(ecsClient.listClusters()).andReturn(ListClustersResponse.builder()
                .clusterArns("arn1", "arn2")
                .build());
        metricCollector.recordLatency(eq(SCRAPE_LATENCY_METRIC), anyObject(), anyLong());
        expect(resourceMapper.map("arn1")).andReturn(Optional.of(resource));
        expect(resourceMapper.map("arn2")).andReturn(Optional.of(resource));

        expect(awsClientProvider.getECSClient("region2")).andReturn(ecsClient);
        expect(ecsClient.listClusters()).andReturn(ListClustersResponse.builder()
                .clusterArns("arn3", "arn4")
                .build());
        metricCollector.recordLatency(eq(SCRAPE_LATENCY_METRIC), anyObject(), anyLong());
        expect(resourceMapper.map("arn3")).andReturn(Optional.of(resource));
        expect(resourceMapper.map("arn4")).andReturn(Optional.of(resource));
        ecsClient.close();
        expectLastCall().times(2);

        expect(objectMapperFactory.getObjectMapper()).andReturn(objectMapper);
        expect(objectMapper.writerWithDefaultPrettyPrinter()).andReturn(objectWriter);

        objectWriter.writeValue(anyObject(File.class), eq(ImmutableList.of(
                mockStaticConfig, mockStaticConfig, mockStaticConfig, mockStaticConfig
        )));
        replayAll();
        ECSServiceDiscoveryExporter testClass = new ECSServiceDiscoveryExporter(scrapeConfigProvider, awsClientProvider,
                resourceMapper, ecsTaskUtil, metricCollector, objectMapperFactory, rateLimiter) {
            @Override
            List<StaticConfig> buildTargetsInCluster(ScrapeConfig sc, EcsClient client, Resource _cluster) {
                assertEquals(scrapeConfig, sc);
                assertEquals(ecsClient, client);
                assertEquals(resource, _cluster);
                return ImmutableList.of(mockStaticConfig);
            }
        };
        testClass.run();

        verifyAll();
    }

    @Test
    public void run_JacksonWriteException() throws Exception {
        expect(scrapeConfigProvider.getScrapeConfig()).andReturn(scrapeConfig);
        expect(scrapeConfig.isECSMonitoringOn()).andReturn(true);
        expect(scrapeConfig.getRegions()).andReturn(ImmutableSet.of("region1", "region2"));

        expect(awsClientProvider.getECSClient("region1")).andReturn(ecsClient);
        expect(ecsClient.listClusters()).andReturn(ListClustersResponse.builder()
                .clusterArns("arn1", "arn2")
                .build());
        metricCollector.recordLatency(eq(SCRAPE_LATENCY_METRIC), anyObject(), anyLong());
        expect(resourceMapper.map("arn1")).andReturn(Optional.of(resource));
        expect(resourceMapper.map("arn2")).andReturn(Optional.of(resource));

        expect(awsClientProvider.getECSClient("region2")).andReturn(ecsClient);
        expect(ecsClient.listClusters()).andReturn(ListClustersResponse.builder()
                .clusterArns("arn3", "arn4")
                .build());
        metricCollector.recordLatency(eq(SCRAPE_LATENCY_METRIC), anyObject(), anyLong());
        expect(resourceMapper.map("arn3")).andReturn(Optional.of(resource));
        expect(resourceMapper.map("arn4")).andReturn(Optional.of(resource));
        ecsClient.close();
        expectLastCall().times(2);

        expect(objectMapperFactory.getObjectMapper()).andReturn(objectMapper);
        expect(objectMapper.writerWithDefaultPrettyPrinter()).andReturn(objectWriter);

        objectWriter.writeValue(anyObject(File.class), eq(ImmutableList.of(
                mockStaticConfig, mockStaticConfig, mockStaticConfig, mockStaticConfig
        )));
        expectLastCall().andThrow(new IOException());
        metricCollector.recordCounterValue(eq(SCRAPE_ERROR_COUNT_METRIC), anyObject(), eq(1));

        replayAll();
        ECSServiceDiscoveryExporter testClass = new ECSServiceDiscoveryExporter(scrapeConfigProvider, awsClientProvider,
                resourceMapper, ecsTaskUtil, metricCollector, objectMapperFactory, rateLimiter) {
            @Override
            List<StaticConfig> buildTargetsInCluster(ScrapeConfig sc, EcsClient client, Resource _cluster) {
                assertEquals(scrapeConfig, sc);
                assertEquals(ecsClient, client);
                assertEquals(resource, _cluster);
                return ImmutableList.of(mockStaticConfig);
            }
        };
        testClass.run();

        verifyAll();
    }

    @Test
    public void run_AWSException() throws Exception {
        expect(scrapeConfigProvider.getScrapeConfig()).andReturn(scrapeConfig);
        expect(scrapeConfig.isECSMonitoringOn()).andReturn(true);
        expect(scrapeConfig.getRegions()).andReturn(ImmutableSet.of("region1", "region2"));

        expect(awsClientProvider.getECSClient("region1")).andReturn(ecsClient);
        expect(ecsClient.listClusters()).andThrow(new RuntimeException());
        metricCollector.recordCounterValue(eq(SCRAPE_ERROR_COUNT_METRIC), anyObject(), eq(1));

        expect(awsClientProvider.getECSClient("region2")).andReturn(ecsClient);
        expect(ecsClient.listClusters()).andThrow(new RuntimeException());
        metricCollector.recordCounterValue(eq(SCRAPE_ERROR_COUNT_METRIC), anyObject(), eq(1));

        expect(objectMapperFactory.getObjectMapper()).andReturn(objectMapper);
        expect(objectMapper.writerWithDefaultPrettyPrinter()).andReturn(objectWriter);
        objectWriter.writeValue(anyObject(File.class), eq(ImmutableList.of()));
        ecsClient.close();
        expectLastCall().times(2);

        replayAll();
        ECSServiceDiscoveryExporter testClass = new ECSServiceDiscoveryExporter(scrapeConfigProvider, awsClientProvider,
                resourceMapper, ecsTaskUtil, metricCollector, objectMapperFactory, rateLimiter);
        testClass.run();

        verifyAll();
    }

    @Test
    public void buildTargetsInCluster() {
        Resource cluster = Resource.builder()
                .region("region1")
                .arn("arn1")
                .name("cluster")
                .type(ECSCluster)
                .build();
        expect(ecsClient.listServices(ListServicesRequest.builder()
                .cluster(cluster.getName())
                .build()))
                .andReturn(ListServicesResponse.builder()
                        .serviceArns("arn1", "arn2")
                        .build());
        expect(resourceMapper.map("arn1")).andReturn(Optional.of(resource));
        expect(resourceMapper.map("arn2")).andReturn(Optional.of(resource));

        replayAll();
        ECSServiceDiscoveryExporter testClass = new ECSServiceDiscoveryExporter(scrapeConfigProvider, awsClientProvider,
                resourceMapper, ecsTaskUtil, metricCollector, objectMapperFactory, rateLimiter) {
            @Override
            List<StaticConfig> buildTargetsInService(ScrapeConfig sc, EcsClient client, Resource _cluster,
                                                     Resource _service) {
                assertEquals(scrapeConfig, sc);
                assertEquals(ecsClient, client);
                assertEquals(cluster, _cluster);
                return ImmutableList.of(mockStaticConfig);
            }
        };
        assertEquals(
                ImmutableList.of(mockStaticConfig, mockStaticConfig),
                testClass.buildTargetsInCluster(scrapeConfig, ecsClient, cluster));

        verifyAll();
    }

    @Test
    public void buildTargetsInService() {
        Resource cluster = Resource.builder()
                .region("region1")
                .arn("cluster-arn")
                .name("cluster")
                .type(ECSCluster)
                .build();

        Resource service = Resource.builder()
                .region("region1")
                .arn("service-arn")
                .name("service")
                .type(ECSService)
                .childOf(cluster)
                .build();

        List<String> taskArns = new ArrayList<>();
        for (int i = 0; i < 101; i++) {
            taskArns.add("arn" + i);
        }

        List<Set<String>> expectedARNs = new ArrayList<>();
        expectedARNs.add(Sets.newHashSet(taskArns.subList(0, 100)));
        expectedARNs.add(Sets.newHashSet(taskArns.subList(100, 101)));
        expect(ecsClient.listTasks(ListTasksRequest.builder()
                .cluster(cluster.getName())
                .serviceName(service.getName())
                .build()))
                .andReturn(ListTasksResponse.builder()
                        .nextToken("token1")
                        .taskArns(expectedARNs.get(0))
                        .build());
        metricCollector.recordLatency(eq(SCRAPE_LATENCY_METRIC), anyObject(), anyLong());

        expect(ecsClient.listTasks(ListTasksRequest.builder()
                .cluster(cluster.getName())
                .serviceName(service.getName())
                .nextToken("token1")
                .build()))
                .andReturn(ListTasksResponse.builder()
                        .taskArns(expectedARNs.get(1))
                        .build());
        metricCollector.recordLatency(eq(SCRAPE_LATENCY_METRIC), anyObject(), anyLong());

        List<Set<String>> actualARNs = new ArrayList<>();

        replayAll();
        ECSServiceDiscoveryExporter testClass = new ECSServiceDiscoveryExporter(scrapeConfigProvider, awsClientProvider,
                resourceMapper, ecsTaskUtil, metricCollector, objectMapperFactory, rateLimiter) {
            @Override
            List<StaticConfig> buildTaskTargets(ScrapeConfig sc, EcsClient client, Resource _cluster,
                                                Resource _service, Set<String> taskIds) {
                assertEquals(scrapeConfig, sc);
                assertEquals(ecsClient, client);
                assertEquals(cluster, _cluster);
                assertEquals(service, _service);
                actualARNs.add(taskIds);
                return ImmutableList.of(mockStaticConfig);
            }
        };
        assertEquals(
                ImmutableList.of(mockStaticConfig, mockStaticConfig),
                testClass.buildTargetsInService(scrapeConfig, ecsClient, cluster, service));
        assertEquals(expectedARNs, actualARNs);

        verifyAll();
    }

    @Test
    public void buildTaskTargets() {
        Resource cluster = Resource.builder()
                .region("region1")
                .arn("cluster-arn")
                .name("cluster")
                .type(ECSCluster)
                .build();

        Resource service = Resource.builder()
                .region("region1")
                .arn("service-arn")
                .name("service")
                .type(ECSService)
                .childOf(cluster)
                .build();

        Set<String> taskArns = new LinkedHashSet<>();
        for (int i = 0; i < 2; i++) {
            taskArns.add("arn" + i);
        }

        Task task1 = Task.builder()
                .taskArn("arn1")
                .build();
        Task task2 = Task.builder()
                .taskArn("arn2")
                .build();

        expect(ecsClient.describeTasks(DescribeTasksRequest.builder()
                .cluster(cluster.getName())
                .tasks(taskArns)
                .build())).andReturn(DescribeTasksResponse.builder()
                .tasks(task1, task2)
                .build());

        expect(ecsTaskUtil.hasAllInfo(task1)).andReturn(true);
        expect(ecsTaskUtil.hasAllInfo(task2)).andReturn(true);

        expect(ecsTaskUtil.buildScrapeTarget(scrapeConfig, ecsClient, cluster, service, task1))
                .andReturn(Optional.of(mockStaticConfig));

        expect(ecsTaskUtil.buildScrapeTarget(scrapeConfig, ecsClient, cluster, service, task2))
                .andReturn(Optional.of(mockStaticConfig));

        replayAll();
        ECSServiceDiscoveryExporter testClass = new ECSServiceDiscoveryExporter(scrapeConfigProvider, awsClientProvider,
                resourceMapper, ecsTaskUtil, metricCollector, objectMapperFactory, rateLimiter);
        assertEquals(
                ImmutableList.of(mockStaticConfig, mockStaticConfig),
                testClass.buildTaskTargets(scrapeConfig, ecsClient, cluster, service, taskArns));

        verifyAll();
    }
}
