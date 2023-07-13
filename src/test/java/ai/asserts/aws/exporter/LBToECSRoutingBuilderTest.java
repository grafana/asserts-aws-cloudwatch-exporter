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
import ai.asserts.aws.resource.ResourceRelation;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import org.easymock.EasyMockSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.ecs.EcsClient;
import software.amazon.awssdk.services.ecs.model.DescribeServicesRequest;
import software.amazon.awssdk.services.ecs.model.DescribeServicesResponse;
import software.amazon.awssdk.services.ecs.model.ListServicesRequest;
import software.amazon.awssdk.services.ecs.model.ListServicesResponse;
import software.amazon.awssdk.services.ecs.model.LoadBalancer;
import software.amazon.awssdk.services.ecs.model.Service;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;

import static ai.asserts.aws.MetricNameUtil.SCRAPE_ACCOUNT_ID_LABEL;
import static ai.asserts.aws.MetricNameUtil.SCRAPE_LATENCY_METRIC;
import static org.easymock.EasyMock.anyLong;
import static org.easymock.EasyMock.anyObject;
import static org.easymock.EasyMock.eq;
import static org.easymock.EasyMock.expect;
import static org.junit.jupiter.api.Assertions.assertEquals;

@SuppressWarnings("unchecked")
public class LBToECSRoutingBuilderTest extends EasyMockSupport {
    private BasicMetricCollector metricCollector;
    private ResourceMapper resourceMapper;
    private TargetGroupLBMapProvider targetGroupLBMapProvider;

    private AWSClientProvider awsClientProvider;
    private AccountProvider accountProvider;

    private EcsClient ecsClient;
    private ECSClusterProvider ecsClusterProvider;

    private LBToECSRoutingBuilder testClass;

    @BeforeEach
    public void setup() {
        metricCollector = mock(BasicMetricCollector.class);
        AWSApiCallRateLimiter rateLimiter = new AWSApiCallRateLimiter(metricCollector, (account) -> "acme");
        resourceMapper = mock(ResourceMapper.class);
        targetGroupLBMapProvider = mock(TargetGroupLBMapProvider.class);
        ecsClient = mock(EcsClient.class);
        awsClientProvider = mock(AWSClientProvider.class);
        accountProvider = mock(AccountProvider.class);
        ecsClusterProvider = mock(ECSClusterProvider.class);
        testClass = new LBToECSRoutingBuilder(rateLimiter, resourceMapper, targetGroupLBMapProvider,
                awsClientProvider, accountProvider, ecsClusterProvider, new TaskExecutorUtil(new TestTaskThreadPool(),
                rateLimiter));
    }

    @Test
    public void run() {
        Resource cluster = Resource.builder()
                .account(SCRAPE_ACCOUNT_ID_LABEL)
                .region("region")
                .arn("cluster-arn")
                .name("cluster")
                .build();

        Resource service = Resource.builder()
                .account(SCRAPE_ACCOUNT_ID_LABEL)
                .region("region")
                .arn("service-arn")
                .childOf(cluster)
                .build();

        Resource tg = Resource.builder()
                .account(SCRAPE_ACCOUNT_ID_LABEL)
                .region("region")
                .arn("tg-arn")
                .build();

        Resource lb = Resource.builder()
                .account(SCRAPE_ACCOUNT_ID_LABEL)
                .region("region")
                .arn("lb-arn")
                .build();

        Map<Resource, Resource> tgToLb = ImmutableMap.of(tg, lb);

        AWSAccount awsAccount = AWSAccount.builder()
                .accountId("account-1")
                .regions(ImmutableSet.of("region"))
                .name("test-account")
                .build();

        expect(accountProvider.getAccounts()).andReturn(ImmutableSet.of(awsAccount));
        expect(awsClientProvider.getECSClient("region", awsAccount)).andReturn(ecsClient);

        expect(ecsClusterProvider.getClusters(awsAccount, "region")).andReturn(ImmutableSet.of(cluster));

        expect(ecsClient.listServices(ListServicesRequest.builder()
                .cluster(cluster.getName())
                .build())).andReturn(ListServicesResponse.builder()
                .nextToken("token1")
                .serviceArns("service-arn", "service-arn1", "service-arn2", "service-arn3", "service-arn4",
                        "service-arn5", "service-arn6", "service-arn7", "service-arn8", "service-arn9",
                        "service-arn10")
                .build());
        metricCollector.recordLatency(eq(SCRAPE_LATENCY_METRIC), anyObject(SortedMap.class), anyLong());

        expect(ecsClient.listServices(ListServicesRequest.builder()
                .cluster(cluster.getName())
                .nextToken("token1")
                .build())).andReturn(ListServicesResponse.builder()
                .nextToken("token1")
                .build());
        metricCollector.recordLatency(eq(SCRAPE_LATENCY_METRIC), anyObject(SortedMap.class), anyLong());

        DescribeServicesRequest request = DescribeServicesRequest.builder()
                .cluster("cluster")
                .services("service-arn", "service-arn1", "service-arn10", "service-arn2", "service-arn3",
                        "service-arn4", "service-arn5", "service-arn6", "service-arn7", "service-arn8")
                .build();

        DescribeServicesResponse response = DescribeServicesResponse.builder()
                .services(Service.builder()
                        .clusterArn(cluster.getName())
                        .serviceArn("service-arn")
                        .loadBalancers(LoadBalancer.builder()
                                .targetGroupArn("tg-arn")
                                .build())
                        .build())
                .build();
        expect(ecsClient.describeServices(request)).andReturn(response);
        metricCollector.recordLatency(eq(SCRAPE_LATENCY_METRIC), anyObject(SortedMap.class), anyLong());

        request = DescribeServicesRequest.builder()
                .cluster("cluster")
                .services("service-arn9")
                .build();

        response = DescribeServicesResponse.builder()
                .services(Collections.emptyList())
                .build();
        expect(ecsClient.describeServices(request)).andReturn(response);
        metricCollector.recordLatency(eq(SCRAPE_LATENCY_METRIC), anyObject(SortedMap.class), anyLong());


        expect(resourceMapper.map("service-arn")).andReturn(Optional.of(service)).times(2);
        expect(resourceMapper.map("tg-arn")).andReturn(Optional.of(tg));

        expect(targetGroupLBMapProvider.getTgToLB()).andReturn(tgToLb);

        replayAll();

        testClass.run();

        Set<ResourceRelation> routings = testClass.getRouting();

        assertEquals(ImmutableSet.of(ResourceRelation.builder()
                .from(lb)
                .to(service)
                .name("ROUTES_TO")
                .build()), routings);

        verifyAll();
    }
}
