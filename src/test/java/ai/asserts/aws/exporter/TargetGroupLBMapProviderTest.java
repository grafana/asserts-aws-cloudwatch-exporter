/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.exporter;

import ai.asserts.aws.AWSClientProvider;
import ai.asserts.aws.TaskExecutorUtil;
import ai.asserts.aws.TestTaskThreadPool;
import ai.asserts.aws.account.AccountProvider;
import ai.asserts.aws.account.AWSAccount;
import ai.asserts.aws.AWSApiCallRateLimiter;
import ai.asserts.aws.resource.Resource;
import ai.asserts.aws.resource.ResourceMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import io.prometheus.client.Collector.MetricFamilySamples;
import io.prometheus.client.Collector.MetricFamilySamples.Sample;
import io.prometheus.client.CollectorRegistry;
import org.easymock.EasyMockSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.elasticloadbalancingv2.ElasticLoadBalancingV2Client;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.Action;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.DescribeListenersRequest;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.DescribeListenersResponse;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.DescribeLoadBalancersResponse;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.DescribeRulesRequest;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.DescribeRulesResponse;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.DescribeTargetGroupsRequest;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.DescribeTargetGroupsResponse;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.DescribeTargetHealthRequest;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.DescribeTargetHealthResponse;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.Listener;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.LoadBalancer;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.Rule;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.TargetDescription;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.TargetGroup;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.TargetHealth;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.TargetHealthDescription;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.TargetHealthStateEnum;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.TargetTypeEnum;

import java.util.List;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;

import static ai.asserts.aws.MetricNameUtil.SCRAPE_ACCOUNT_ID_LABEL;
import static ai.asserts.aws.MetricNameUtil.SCRAPE_LATENCY_METRIC;
import static ai.asserts.aws.MetricNameUtil.SCRAPE_OPERATION_LABEL;
import static ai.asserts.aws.MetricNameUtil.SCRAPE_REGION_LABEL;
import static org.easymock.EasyMock.anyLong;
import static org.easymock.EasyMock.anyObject;
import static org.easymock.EasyMock.eq;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.expectLastCall;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SuppressWarnings("unchecked")
public class TargetGroupLBMapProviderTest extends EasyMockSupport {
    private AccountProvider accountProvider;
    private AWSClientProvider awsClientProvider;
    private ElasticLoadBalancingV2Client lbClient;
    private ResourceMapper resourceMapper;
    private BasicMetricCollector metricCollector;
    private MetricSampleBuilder sampleBuilder;
    private CollectorRegistry collectorRegistry;
    private AWSApiCallRateLimiter rateLimiter;
    private TaskExecutorUtil taskExecutorUtil;
    private Sample mockSample;
    private MetricFamilySamples mockFamilySamples;
    private SortedMap<String, String> labels;
    private AWSAccount awsAccount;

    @BeforeEach
    public void setup() {
        accountProvider = mock(AccountProvider.class);
        awsClientProvider = mock(AWSClientProvider.class);
        lbClient = mock(ElasticLoadBalancingV2Client.class);
        resourceMapper = mock(ResourceMapper.class);
        metricCollector = mock(BasicMetricCollector.class);
        sampleBuilder = mock(MetricSampleBuilder.class);
        collectorRegistry = mock(CollectorRegistry.class);
        mockSample = mock(Sample.class);
        mockFamilySamples = mock(MetricFamilySamples.class);
        rateLimiter = new AWSApiCallRateLimiter(metricCollector, (account) -> "tenant");
        taskExecutorUtil = new TaskExecutorUtil(new TestTaskThreadPool(), rateLimiter);
        labels = ImmutableSortedMap.of(
                SCRAPE_ACCOUNT_ID_LABEL, "account",
                SCRAPE_REGION_LABEL, "region",
                SCRAPE_OPERATION_LABEL, "ElasticLoadBalancingV2Client/describeLoadBalancers"
        );

        awsAccount = new AWSAccount("tenant", "account", "", "", "role",
                ImmutableSet.of("region"));
        expect(accountProvider.getAccounts()).andReturn(ImmutableSet.of(awsAccount)).anyTimes();
    }


    @Test
    public void afterPropertiesSet() throws Exception {
        TargetGroupLBMapProvider testClass = new TargetGroupLBMapProvider(accountProvider, awsClientProvider,
                resourceMapper, rateLimiter, sampleBuilder, collectorRegistry,
                new TaskExecutorUtil(new TestTaskThreadPool(), rateLimiter));
        collectorRegistry.register(testClass);
        replayAll();
        testClass.afterPropertiesSet();
        verifyAll();
    }

    @Test
    public void update() {
        LoadBalancer loadBalancer = LoadBalancer.builder()
                .loadBalancerArn("lb-arn")
                .build();

        expect(awsClientProvider.getELBV2Client("region", awsAccount)).andReturn(lbClient);

        expect(lbClient.describeLoadBalancers()).andReturn(DescribeLoadBalancersResponse.builder()
                .loadBalancers(loadBalancer)
                .build());
        metricCollector.recordLatency(eq(SCRAPE_LATENCY_METRIC), anyObject(SortedMap.class), anyLong());

        AtomicInteger sideEffect = new AtomicInteger();

        TargetGroupLBMapProvider testClass = new TargetGroupLBMapProvider(accountProvider, awsClientProvider,
                resourceMapper, rateLimiter, sampleBuilder, collectorRegistry, taskExecutorUtil) {
            @Override
            List<Sample> mapLB(ElasticLoadBalancingV2Client client,
                               SortedMap<String, String> theLabels,
                               LoadBalancer theLb) {
                assertEquals(lbClient, client);
                assertEquals(labels, theLabels);
                assertEquals(loadBalancer, theLb);
                sideEffect.incrementAndGet();
                return ImmutableList.of(mockSample);
            }
        };
        expect(sampleBuilder.buildFamily(ImmutableList.of(mockSample))).andReturn(Optional.of(mockFamilySamples));
        replayAll();
        testClass.update();
        assertEquals(1, sideEffect.get());
        assertEquals(mockFamilySamples, testClass.getMetricFamilySamples());
        verifyAll();
    }

    @Test
    public void mapLB() {
        Resource lbResource = Resource.builder()
                .arn("lb-arn")
                .build();

        LoadBalancer loadBalancer = LoadBalancer.builder()
                .loadBalancerArn("lb-arn")
                .build();

        Listener listener = Listener.builder()
                .listenerArn("listener-arn")
                .build();

        DescribeListenersRequest request = DescribeListenersRequest.builder()
                .loadBalancerArn("lb-arn")
                .build();

        DescribeListenersResponse response = DescribeListenersResponse.builder()
                .listeners(listener)
                .build();
        expect(resourceMapper.map(loadBalancer.loadBalancerArn())).andReturn(Optional.of(lbResource));
        expect(lbClient.describeListeners(request)).andReturn(response);
        metricCollector.recordLatency(eq(SCRAPE_LATENCY_METRIC), anyObject(SortedMap.class), anyLong());

        AtomicInteger sideEffect = new AtomicInteger();

        TargetGroupLBMapProvider testClass = new TargetGroupLBMapProvider(accountProvider, awsClientProvider,
                resourceMapper, rateLimiter, sampleBuilder, collectorRegistry, taskExecutorUtil) {
            @Override
            List<Sample> mapListener(ElasticLoadBalancingV2Client theClient,
                                     SortedMap<String, String> labels,
                                     Resource theResource, Listener theListener) {
                assertEquals(lbClient, theClient);
                assertEquals(lbResource, theResource);
                assertEquals(listener, theListener);
                sideEffect.incrementAndGet();
                return ImmutableList.of(mockSample);
            }
        };

        replayAll();
        assertEquals(ImmutableList.of(mockSample), testClass.mapLB(lbClient, labels, loadBalancer));
        assertEquals(1, sideEffect.get());
        verifyAll();
    }

    @Test
    public void mapListener() {
        Resource lbResource = Resource.builder()
                .name("lb-name")
                .id("lb-id")
                .arn("lb-arn")
                .account("account")
                .region("us-west-2")
                .build();

        Resource tgResource = Resource.builder()
                .name("tg")
                .account("account")
                .region("us-west-2")
                .arn("tg-arn")
                .build();

        Resource tgResource2 = Resource.builder()
                .name("tg2")
                .account("account")
                .region("us-west-2")
                .arn("tg-arn2")
                .build();

        Listener listener = Listener.builder()
                .listenerArn("listener-arn")
                .build();

        DescribeRulesRequest request = DescribeRulesRequest.builder()
                .listenerArn("listener-arn")
                .build();

        DescribeRulesResponse response = DescribeRulesResponse.builder()
                .rules(Rule.builder()
                        .actions(Action.builder()
                                        .targetGroupArn("tg-arn")
                                        .build(),
                                Action.builder()
                                        .targetGroupArn("tg-arn2")
                                        .build())
                        .build())
                .build();
        expect(lbClient.describeRules(request)).andReturn(response);
        expect(lbClient.describeTargetGroups(DescribeTargetGroupsRequest.builder()
                .targetGroupArns("tg-arn")
                .build())).andReturn(DescribeTargetGroupsResponse.builder()
                .targetGroups(TargetGroup.builder()
                        .healthCheckPort("80")
                        .targetType(TargetTypeEnum.INSTANCE)
                        .build())
                .build());
        expect(lbClient.describeTargetHealth(DescribeTargetHealthRequest.builder().targetGroupArn("tg-arn")
                .build())).andReturn(DescribeTargetHealthResponse.builder()
                .targetHealthDescriptions(TargetHealthDescription.builder()
                        .target(TargetDescription.builder()
                                .id("instance-id")
                                .port(80)
                                .build())
                        .targetHealth(TargetHealth.builder()
                                .state(TargetHealthStateEnum.HEALTHY)
                                .build())
                        .build())
                .build());

        expect(lbClient.describeTargetGroups(DescribeTargetGroupsRequest.builder()
                .targetGroupArns("tg-arn2")
                .build())).andReturn(DescribeTargetGroupsResponse.builder()
                .targetGroups(TargetGroup.builder()
                        .healthCheckPort("80")
                        .targetType(TargetTypeEnum.INSTANCE)
                        .build())
                .build());
        expect(lbClient.describeTargetHealth(DescribeTargetHealthRequest.builder().targetGroupArn("tg-arn2")
                .build())).andReturn(DescribeTargetHealthResponse.builder()
                .targetHealthDescriptions(TargetHealthDescription.builder()
                        .target(TargetDescription.builder()
                                .id("instance-id2")
                                .port(80)
                                .build())
                        .targetHealth(TargetHealth.builder()
                                .state(TargetHealthStateEnum.HEALTHY)
                                .build())
                        .build())
                .build());
        metricCollector.recordLatency(eq(SCRAPE_LATENCY_METRIC), anyObject(SortedMap.class), anyLong());
        expectLastCall().anyTimes();

        expect(resourceMapper.map("tg-arn")).andReturn(Optional.of(tgResource));
        expect(resourceMapper.map("tg-arn2")).andReturn(Optional.of(tgResource2));

        TargetGroupLBMapProvider testClass = new TargetGroupLBMapProvider(accountProvider, awsClientProvider,
                resourceMapper, rateLimiter, sampleBuilder, collectorRegistry, taskExecutorUtil);

        testClass.getMissingTgMap().put(tgResource2, tgResource2);

        ImmutableMap<String, String> labels = ImmutableMap.<String, String>builder()
                .put("account_id", "account")
                .put("region", "us-west-2")
                .put("ec2_instance_id", "instance-id")
                .put("port", "80")
                .put("lb_name", "lb-name")
                .put("lb_id", "lb-id")
                .put("lb_type", "classic")
                .build();
        expect(sampleBuilder.buildSingleSample("aws_lb_to_ec2_instance", labels, 1.0D))
                .andReturn(Optional.of(mockSample));

        labels = ImmutableMap.<String, String>builder()
                .put("account_id", "account")
                .put("region", "us-west-2")
                .put("ec2_instance_id", "instance-id2")
                .put("port", "80")
                .put("lb_name", "lb-name")
                .put("lb_id", "lb-id")
                .put("lb_type", "classic")
                .build();
        expect(sampleBuilder.buildSingleSample("aws_lb_to_ec2_instance", labels, 1.0D))
                .andReturn(Optional.of(mockSample));

        replayAll();
        assertTrue(testClass.getTgToLB().isEmpty());
        assertEquals(ImmutableList.of(
                mockSample, mockSample
        ), testClass.mapListener(lbClient, new TreeMap<>(), lbResource, listener));
        assertEquals(ImmutableMap.of(tgResource, lbResource), testClass.getTgToLB());
        verifyAll();
    }

    @Test
    public void handleMissing() {
        TargetGroupLBMapProvider testClass = new TargetGroupLBMapProvider(accountProvider, awsClientProvider,
                resourceMapper, rateLimiter, sampleBuilder, collectorRegistry, taskExecutorUtil);

        Resource tgResource = Resource.builder()
                .name("tg")
                .arn("tg-arn")
                .build();

        testClass.getTgToLB().put(tgResource, tgResource);
        testClass.handleMissingTgs(ImmutableSet.of(tgResource));
        assertTrue(testClass.getTgToLB().isEmpty());
        assertTrue(testClass.getMissingTgMap().containsKey(tgResource));
        assertEquals(tgResource, testClass.getMissingTgMap().get(tgResource));
    }
}
