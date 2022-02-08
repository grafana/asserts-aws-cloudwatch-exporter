/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.exporter;

import ai.asserts.aws.RateLimiter;
import ai.asserts.aws.resource.Resource;
import ai.asserts.aws.resource.ResourceMapper;
import ai.asserts.aws.resource.ResourceRelation;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import org.easymock.EasyMockSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.ecs.EcsClient;
import software.amazon.awssdk.services.ecs.model.DescribeServicesRequest;
import software.amazon.awssdk.services.ecs.model.DescribeServicesResponse;
import software.amazon.awssdk.services.ecs.model.LoadBalancer;
import software.amazon.awssdk.services.ecs.model.Service;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;

import static ai.asserts.aws.MetricNameUtil.SCRAPE_LATENCY_METRIC;
import static org.easymock.EasyMock.anyLong;
import static org.easymock.EasyMock.anyObject;
import static org.easymock.EasyMock.eq;
import static org.easymock.EasyMock.expect;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class LBToECSRoutingBuilderTest extends EasyMockSupport {
    private BasicMetricCollector metricCollector;
    private ResourceMapper resourceMapper;
    private TargetGroupLBMapProvider targetGroupLBMapProvider;

    private EcsClient ecsClient;

    private LBToECSRoutingBuilder testClass;

    @BeforeEach
    public void setup() {
        metricCollector = mock(BasicMetricCollector.class);
        RateLimiter rateLimiter = new RateLimiter(metricCollector);
        resourceMapper = mock(ResourceMapper.class);
        targetGroupLBMapProvider = mock(TargetGroupLBMapProvider.class);
        ecsClient = mock(EcsClient.class);
        testClass = new LBToECSRoutingBuilder(rateLimiter, resourceMapper, targetGroupLBMapProvider);
    }

    @Test
    public void getRoutings() {
        Resource cluster = Resource.builder()
                .account("account")
                .region("region")
                .arn("cluster-arn")
                .build();

        Resource service = Resource.builder()
                .account("account")
                .region("region")
                .arn("service-arn")
                .build();

        Resource tg = Resource.builder()
                .account("account")
                .region("region")
                .arn("tg-arn")
                .build();

        Resource lb = Resource.builder()
                .account("account")
                .region("region")
                .arn("lb-arn")
                .build();

        Map<Resource, Resource> tgToLb = ImmutableMap.of(tg, lb);

        DescribeServicesRequest request = DescribeServicesRequest.builder()
                .cluster("cluster-arn")
                .services("service-arn")
                .build();

        DescribeServicesResponse response = DescribeServicesResponse.builder()
                .services(Service.builder()
                        .serviceArn("service-arn")
                        .loadBalancers(LoadBalancer.builder()
                                .targetGroupArn("tg-arn")
                                .build())
                        .build())
                .build();


        expect(ecsClient.describeServices(request)).andReturn(response);
        metricCollector.recordLatency(eq(SCRAPE_LATENCY_METRIC), anyObject(SortedMap.class), anyLong());
        expect(resourceMapper.map("service-arn")).andReturn(Optional.of(service)).times(2);
        expect(resourceMapper.map("tg-arn")).andReturn(Optional.of(tg));

        expect(targetGroupLBMapProvider.getTgToLB()).andReturn(tgToLb);

        replayAll();

        Set<ResourceRelation> routings = testClass.getRoutings(ecsClient, cluster, ImmutableList.of(service));

        assertEquals(ImmutableSet.of(ResourceRelation.builder()
                .from(lb)
                .to(service)
                .name("ROUTES_TO")
                .build()), routings);

        verifyAll();
    }
}
