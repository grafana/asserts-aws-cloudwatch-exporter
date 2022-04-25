/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.exporter;

import ai.asserts.aws.AWSClientProvider;
import ai.asserts.aws.AccountProvider;
import ai.asserts.aws.AccountProvider.AWSAccount;
import ai.asserts.aws.RateLimiter;
import ai.asserts.aws.resource.Resource;
import ai.asserts.aws.resource.ResourceMapper;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
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
import software.amazon.awssdk.services.elasticloadbalancingv2.model.Listener;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.LoadBalancer;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.Rule;

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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TargetGroupLBMapProviderTest extends EasyMockSupport {
    private AccountProvider accountProvider;
    private AWSClientProvider awsClientProvider;
    private ElasticLoadBalancingV2Client lbClient;
    private ResourceMapper resourceMapper;
    private BasicMetricCollector metricCollector;
    private SortedMap<String, String> labels;

    @BeforeEach
    public void setup() {
        accountProvider = mock(AccountProvider.class);
        awsClientProvider = mock(AWSClientProvider.class);
        lbClient = mock(ElasticLoadBalancingV2Client.class);
        resourceMapper = mock(ResourceMapper.class);
        metricCollector = mock(BasicMetricCollector.class);
        labels = ImmutableSortedMap.of(
                SCRAPE_ACCOUNT_ID_LABEL, "account",
                SCRAPE_REGION_LABEL, "region",
                SCRAPE_OPERATION_LABEL, "ElasticLoadBalancingV2Client/describeLoadBalancers"
        );

        expect(accountProvider.getAccounts()).andReturn(ImmutableSet.of(
                new AWSAccount("account", "role", ImmutableSet.of("region"))
        )).anyTimes();
    }

    @Test
    public void update() {
        LoadBalancer loadBalancer = LoadBalancer.builder()
                .loadBalancerArn("lb-arn")
                .build();

        expect(awsClientProvider.getELBV2Client("region", "role")).andReturn(lbClient);

        expect(lbClient.describeLoadBalancers()).andReturn(DescribeLoadBalancersResponse.builder()
                .loadBalancers(loadBalancer)
                .build());
        metricCollector.recordLatency(eq(SCRAPE_LATENCY_METRIC), anyObject(SortedMap.class), anyLong());

        AtomicInteger sideEffect = new AtomicInteger();

        TargetGroupLBMapProvider testClass = new TargetGroupLBMapProvider(accountProvider, awsClientProvider,
                resourceMapper, new RateLimiter(metricCollector)) {
            @Override
            void mapLB(ElasticLoadBalancingV2Client client, SortedMap<String, String> theLabels,
                       LoadBalancer theLb) {
                assertEquals(lbClient, client);
                assertEquals(labels, theLabels);
                assertEquals(loadBalancer, theLb);
                sideEffect.incrementAndGet();
            }
        };

        lbClient.close();

        replayAll();
        testClass.update();
        assertEquals(1, sideEffect.get());
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
                resourceMapper, new RateLimiter(metricCollector)) {
            @Override
            void mapListener(ElasticLoadBalancingV2Client theClient, SortedMap<String, String> labels,
                             Resource theResource, Listener theListener) {
                assertEquals(lbClient, theClient);
                assertEquals(lbResource, theResource);
                assertEquals(listener, theListener);
                sideEffect.incrementAndGet();
            }
        };

        replayAll();
        testClass.mapLB(lbClient, labels, loadBalancer);
        assertEquals(1, sideEffect.get());
        verifyAll();
    }

    @Test
    public void mapListener() {
        Resource lbResource = Resource.builder()
                .arn("lb-arn")
                .build();

        Resource tgResource = Resource.builder()
                .arn("tg-arn")
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
                                .build())
                        .build())
                .build();
        expect(lbClient.describeRules(request)).andReturn(response);
        metricCollector.recordLatency(eq(SCRAPE_LATENCY_METRIC), anyObject(SortedMap.class), anyLong());

        expect(resourceMapper.map("tg-arn")).andReturn(Optional.of(tgResource));

        TargetGroupLBMapProvider testClass = new TargetGroupLBMapProvider(accountProvider, awsClientProvider,
                resourceMapper, new RateLimiter(metricCollector));

        replayAll();
        assertTrue(testClass.getTgToLB().isEmpty());
        testClass.mapListener(lbClient, new TreeMap<>(), lbResource, listener);
        assertEquals(ImmutableMap.of(tgResource, lbResource), testClass.getTgToLB());
        verifyAll();
    }
}
