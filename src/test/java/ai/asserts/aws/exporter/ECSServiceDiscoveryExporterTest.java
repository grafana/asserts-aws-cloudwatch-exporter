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
import ai.asserts.aws.config.ScrapeConfig;
import ai.asserts.aws.exporter.ECSServiceDiscoveryExporter.StaticConfig;
import ai.asserts.aws.resource.Resource;
import ai.asserts.aws.resource.ResourceMapper;
import ai.asserts.aws.resource.ResourceRelation;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Sets;
import io.prometheus.client.Collector.MetricFamilySamples;
import io.prometheus.client.Collector.MetricFamilySamples.Sample;
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
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

import static ai.asserts.aws.MetricNameUtil.SCRAPE_ERROR_COUNT_METRIC;
import static ai.asserts.aws.MetricNameUtil.SCRAPE_LATENCY_METRIC;
import static ai.asserts.aws.resource.ResourceType.ECSCluster;
import static ai.asserts.aws.resource.ResourceType.ECSService;
import static org.easymock.EasyMock.anyLong;
import static org.easymock.EasyMock.anyObject;
import static org.easymock.EasyMock.anyString;
import static org.easymock.EasyMock.eq;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.expectLastCall;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class ECSServiceDiscoveryExporterTest extends EasyMockSupport {
    private AWSAccount account;
    private AccountProvider accountProvider;
    private ScrapeConfigProvider scrapeConfigProvider;
    private ScrapeConfig scrapeConfig;
    private AWSClientProvider awsClientProvider;
    private EcsClient ecsClient;
    private LBToECSRoutingBuilder lbToECSRoutingBuilder;
    private ResourceMapper resourceMapper;
    private Resource resource;
    private ECSTaskUtil ecsTaskUtil;
    private BasicMetricCollector metricCollector;
    private ObjectMapperFactory objectMapperFactory;
    private RateLimiter rateLimiter;
    private ObjectMapper objectMapper;
    private ObjectWriter objectWriter;
    private MetricSampleBuilder metricSampleBuilder;
    private StaticConfig mockStaticConfig;
    private ResourceRelation mockRelation;
    private Sample sample;
    private MetricFamilySamples metricFamilySamples;

    @BeforeEach
    public void setup() {
        account = new AWSAccount("account", "", "", "",
                ImmutableSet.of("region1", "region2"));
        accountProvider = mock(AccountProvider.class);
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
        lbToECSRoutingBuilder = mock(LBToECSRoutingBuilder.class);
        mockRelation = mock(ResourceRelation.class);
        metricSampleBuilder = mock(MetricSampleBuilder.class);
        sample = mock(Sample.class);
        metricFamilySamples = mock(MetricFamilySamples.class);

        rateLimiter = new RateLimiter(metricCollector);
        resetAll();
    }

    @Test
    public void updateCollect() throws Exception {
        expect(accountProvider.getAccounts()).andReturn(ImmutableSet.of(account));
        expect(scrapeConfigProvider.getScrapeConfig()).andReturn(scrapeConfig);
        expect(scrapeConfig.getEcsTargetSDFile()).andReturn("ecs-sd-file.yml");
        expect(scrapeConfig.isDiscoverECSTasks()).andReturn(true);

        expect(awsClientProvider.getECSClient("region1", account)).andReturn(ecsClient);
        expect(ecsClient.listClusters()).andReturn(ListClustersResponse.builder()
                .clusterArns("arn1", "arn2")
                .build());
        metricCollector.recordLatency(eq(SCRAPE_LATENCY_METRIC), anyObject(), anyLong());
        expect(resourceMapper.map("arn1")).andReturn(Optional.of(resource));
        expect(resourceMapper.map("arn2")).andReturn(Optional.of(resource));

        expect(awsClientProvider.getECSClient("region2", account)).andReturn(ecsClient);
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

        expect(objectWriter.writeValueAsString(eq(ImmutableList.of(
                mockStaticConfig, mockStaticConfig, mockStaticConfig, mockStaticConfig
        )))).andReturn("content");

        expect(metricSampleBuilder.buildFamily(ImmutableList.of(sample, sample, sample, sample)))
                .andReturn(metricFamilySamples);

        replayAll();
        ECSServiceDiscoveryExporter testClass = new ECSServiceDiscoveryExporter(
                accountProvider, scrapeConfigProvider, awsClientProvider,
                resourceMapper, ecsTaskUtil, objectMapperFactory, rateLimiter,
                lbToECSRoutingBuilder, metricSampleBuilder) {
            @Override
            List<StaticConfig> buildTargetsInCluster(ScrapeConfig sc, EcsClient client,
                                                     Resource _cluster,
                                                     Set<ResourceRelation> routing,
                                                     List<Sample> samples) {
                assertEquals(scrapeConfig, sc);
                assertEquals(ecsClient, client);
                assertEquals(resource, _cluster);
                samples.add(sample);
                return ImmutableList.of(mockStaticConfig);
            }
        };
        testClass.update();
        assertEquals(ImmutableList.of(metricFamilySamples), testClass.collect());

        verifyAll();
    }

    @Test
    public void update_JacksonWriteException() throws Exception {
        expect(accountProvider.getAccounts()).andReturn(ImmutableSet.of(account));
        expect(scrapeConfigProvider.getScrapeConfig()).andReturn(scrapeConfig);
        expect(scrapeConfig.getEcsTargetSDFile()).andReturn("ecs-sd-file.yml");
        expect(scrapeConfig.isDiscoverECSTasks()).andReturn(true);

        expect(awsClientProvider.getECSClient("region1", account)).andReturn(ecsClient);
        expect(ecsClient.listClusters()).andReturn(ListClustersResponse.builder()
                .clusterArns("arn1", "arn2")
                .build());
        metricCollector.recordLatency(eq(SCRAPE_LATENCY_METRIC), anyObject(), anyLong());
        expect(resourceMapper.map("arn1")).andReturn(Optional.of(resource));
        expect(resourceMapper.map("arn2")).andReturn(Optional.of(resource));

        expect(awsClientProvider.getECSClient("region2", account)).andReturn(ecsClient);
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

        replayAll();
        ECSServiceDiscoveryExporter testClass = new ECSServiceDiscoveryExporter(
                accountProvider, scrapeConfigProvider, awsClientProvider,
                resourceMapper, ecsTaskUtil, objectMapperFactory, rateLimiter, lbToECSRoutingBuilder,
                metricSampleBuilder) {
            @Override
            List<StaticConfig> buildTargetsInCluster(ScrapeConfig sc, EcsClient client, Resource _cluster,
                                                     Set<ResourceRelation> routing,
                                                     List<Sample> samples) {
                assertEquals(scrapeConfig, sc);
                assertEquals(ecsClient, client);
                assertEquals(resource, _cluster);
                return ImmutableList.of(mockStaticConfig);
            }
        };
        testClass.update();

        verifyAll();
    }

    @Test
    public void update_AWSException() throws Exception {
        expect(accountProvider.getAccounts()).andReturn(ImmutableSet.of(account));
        expect(scrapeConfigProvider.getScrapeConfig()).andReturn(scrapeConfig);
        expect(scrapeConfig.getEcsTargetSDFile()).andReturn("ecs-sd-file.yml");
        expect(scrapeConfig.isDiscoverECSTasks()).andReturn(true);

        expect(awsClientProvider.getECSClient("region1", account)).andReturn(ecsClient);
        expect(ecsClient.listClusters()).andThrow(new RuntimeException());
        metricCollector.recordLatency(anyString(), anyObject(), anyLong());
        metricCollector.recordCounterValue(eq(SCRAPE_ERROR_COUNT_METRIC), anyObject(), eq(1));

        expect(awsClientProvider.getECSClient("region2", account)).andReturn(ecsClient);
        expect(ecsClient.listClusters()).andThrow(new RuntimeException());
        metricCollector.recordLatency(anyString(), anyObject(), anyLong());
        metricCollector.recordCounterValue(eq(SCRAPE_ERROR_COUNT_METRIC), anyObject(), eq(1));

        expect(objectMapperFactory.getObjectMapper()).andReturn(objectMapper);
        expect(objectMapper.writerWithDefaultPrettyPrinter()).andReturn(objectWriter);
        objectWriter.writeValue(anyObject(File.class), eq(ImmutableList.of()));
        expect(objectWriter.writeValueAsString(eq(ImmutableList.of()))).andReturn("content");
        ecsClient.close();
        expectLastCall().times(2);

        replayAll();
        ECSServiceDiscoveryExporter testClass = new ECSServiceDiscoveryExporter(
                accountProvider, scrapeConfigProvider, awsClientProvider,
                resourceMapper, ecsTaskUtil, objectMapperFactory, rateLimiter,
                lbToECSRoutingBuilder, metricSampleBuilder);
        testClass.update();

        verifyAll();
    }

    @Test
    public void buildTargetsInCluster() {
        Set<ResourceRelation> newRouting = new HashSet<>();
        List<Sample> samples = new ArrayList<>();
        expect(scrapeConfig.isDiscoverECSTasks()).andReturn(true).anyTimes();
        expect(scrapeConfig.getECSConfigByNameAndPort()).andReturn(ImmutableSortedMap.of()).anyTimes();
        Resource cluster = Resource.builder()
                .region("region1")
                .arn("arn1")
                .name("cluster")
                .account("account")
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
        metricCollector.recordLatency(anyString(), anyObject(), anyLong());

        expect(lbToECSRoutingBuilder.getRoutings(ecsClient, cluster, ImmutableList.of(resource, resource)))
                .andReturn(ImmutableSet.of(mockRelation));

        expect(resource.getName()).andReturn("service1").times(2);
        expect(resource.getName()).andReturn("service2").times(2);

        Map<String, String> labels1 = new TreeMap<>();
        labels1.put("account_id", "account");
        labels1.put("cluster", "cluster");
        labels1.put("job", "service1");
        labels1.put("name", "service1");
        labels1.put("region", "region1");
        labels1.put("aws_resource_type", "AWS::ECS::Service");
        labels1.put("namespace", "AWS/ECS");
        expect(metricSampleBuilder.buildSingleSample("aws_resource", labels1, 1.0D)).andReturn(sample);

        Map<String, String> labels2 = new TreeMap<>();
        labels2.put("account_id", "account");
        labels2.put("cluster", "cluster");
        labels2.put("job", "service2");
        labels2.put("name", "service2");
        labels2.put("region", "region1");
        labels2.put("aws_resource_type", "AWS::ECS::Service");
        labels2.put("namespace", "AWS/ECS");
        expect(metricSampleBuilder.buildSingleSample("aws_resource", labels2, 1.0D)).andReturn(sample);

        replayAll();
        ECSServiceDiscoveryExporter testClass = new ECSServiceDiscoveryExporter(accountProvider,
                scrapeConfigProvider, awsClientProvider, resourceMapper, ecsTaskUtil, objectMapperFactory, rateLimiter,
                lbToECSRoutingBuilder, metricSampleBuilder) {
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
                testClass.buildTargetsInCluster(scrapeConfig, ecsClient, cluster, newRouting, samples));

        assertEquals(ImmutableSet.of(mockRelation), newRouting);

        verifyAll();
    }

    @Test
    public void buildTargetsInService() {
        Resource cluster = Resource.builder()
                .region("region1")
                .arn("cluster-arn")
                .name("cluster")
                .account("account")
                .type(ECSCluster)
                .build();

        Resource service = Resource.builder()
                .region("region1")
                .arn("service-arn")
                .name("service")
                .account("account")
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
        ECSServiceDiscoveryExporter testClass = new ECSServiceDiscoveryExporter(
                accountProvider, scrapeConfigProvider, awsClientProvider,
                resourceMapper, ecsTaskUtil, objectMapperFactory, rateLimiter,
                lbToECSRoutingBuilder, metricSampleBuilder) {
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
                .account("account")
                .type(ECSCluster)
                .build();

        Resource service = Resource.builder()
                .region("region1")
                .arn("service-arn")
                .name("service")
                .account("account")
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
        metricCollector.recordLatency(anyString(), anyObject(), anyLong());

        expect(ecsTaskUtil.hasAllInfo(task1)).andReturn(true);
        expect(ecsTaskUtil.hasAllInfo(task2)).andReturn(true);

        expect(ecsTaskUtil.buildScrapeTargets(scrapeConfig, ecsClient, cluster, service, task1))
                .andReturn(ImmutableList.of(mockStaticConfig));

        expect(ecsTaskUtil.buildScrapeTargets(scrapeConfig, ecsClient, cluster, service, task2))
                .andReturn(ImmutableList.of(mockStaticConfig));

        replayAll();
        ECSServiceDiscoveryExporter testClass = new ECSServiceDiscoveryExporter(
                accountProvider, scrapeConfigProvider, awsClientProvider,
                resourceMapper, ecsTaskUtil, objectMapperFactory, rateLimiter, lbToECSRoutingBuilder,
                metricSampleBuilder);
        assertEquals(
                ImmutableList.of(mockStaticConfig, mockStaticConfig),
                testClass.buildTaskTargets(scrapeConfig, ecsClient, cluster, service, taskArns));

        verifyAll();
    }
}
