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
import ai.asserts.aws.resource.ResourceType;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import org.easymock.EasyMockSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.elasticloadbalancingv2.ElasticLoadBalancingV2Client;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.DescribeTargetHealthRequest;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.DescribeTargetHealthResponse;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.TargetDescription;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.TargetGroupNotFoundException;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.TargetHealthDescription;

import java.util.Optional;
import java.util.SortedMap;

import static org.easymock.EasyMock.anyInt;
import static org.easymock.EasyMock.anyLong;
import static org.easymock.EasyMock.anyObject;
import static org.easymock.EasyMock.anyString;
import static org.easymock.EasyMock.expect;
import static org.junit.jupiter.api.Assertions.assertEquals;

@SuppressWarnings("unchecked")
public class LBToLambdaRoutingBuilderTest extends EasyMockSupport {
    private ElasticLoadBalancingV2Client elbV2Client;
    private BasicMetricCollector metricCollector;
    private ResourceMapper resourceMapper;
    private Resource targetGroupResource;
    private Resource targetGroupResource2;
    private Resource lbResource;
    private Resource lambdaResource;
    TargetGroupLBMapProvider targetGroupLBMapProvider;
    private LBToLambdaRoutingBuilder testClass;

    @BeforeEach
    public void setup() {
        AWSClientProvider awsClientProvider = mock(AWSClientProvider.class);
        AccountProvider accountProvider = mock(AccountProvider.class);
        targetGroupLBMapProvider = mock(TargetGroupLBMapProvider.class);

        metricCollector = mock(BasicMetricCollector.class);
        elbV2Client = mock(ElasticLoadBalancingV2Client.class);
        resourceMapper = mock(ResourceMapper.class);

        targetGroupResource = mock(Resource.class);
        targetGroupResource2 = mock(Resource.class);
        lbResource = mock(Resource.class);
        Resource lbResource2 = mock(Resource.class);
        lambdaResource = mock(Resource.class);

        testClass = new LBToLambdaRoutingBuilder(awsClientProvider,
                new AWSApiCallRateLimiter(metricCollector, (account) -> "acme"),
                resourceMapper, accountProvider, targetGroupLBMapProvider,
                new TaskExecutorUtil(new TestTaskThreadPool(),
                        new AWSApiCallRateLimiter(metricCollector, (account) -> "acme")));

        AWSAccount awsAccount = new AWSAccount("acme", "account", "accessId", "secretKey", "role",
                ImmutableSet.of("region"));
        expect(accountProvider.getAccounts()).andReturn(ImmutableSet.of(awsAccount)).anyTimes();
        expect(awsClientProvider.getELBV2Client("region", awsAccount)).andReturn(elbV2Client).anyTimes();
        expect(targetGroupLBMapProvider.getTgToLB()).andReturn(ImmutableMap.of(
                targetGroupResource, lbResource, targetGroupResource2, lbResource2)).anyTimes();
    }

    @Test
    void getRoutings() {
        expect(targetGroupResource.getAccount()).andReturn("account");
        expect(targetGroupResource.getRegion()).andReturn("region");
        expect(targetGroupResource.getArn()).andReturn("tg-arn");
        expect(targetGroupResource2.getAccount()).andReturn("account");
        expect(targetGroupResource2.getRegion()).andReturn("region");
        expect(targetGroupResource2.getArn()).andReturn("tg-arn2");
        expect(elbV2Client.describeTargetHealth(DescribeTargetHealthRequest.builder()
                .targetGroupArn("tg-arn")
                .build())).andReturn(DescribeTargetHealthResponse.builder()
                .targetHealthDescriptions(TargetHealthDescription.builder()
                        .target(TargetDescription.builder()
                                .id("lambda-arn")
                                .build())
                        .build())
                .build());
        expect(elbV2Client.describeTargetHealth(DescribeTargetHealthRequest.builder()
                .targetGroupArn("tg-arn2")
                .build())).andThrow(TargetGroupNotFoundException.builder().build());
        expect(resourceMapper.map("lambda-arn")).andReturn(Optional.of(lambdaResource));
        expect(lambdaResource.getType()).andReturn(ResourceType.LambdaFunction).anyTimes();

        metricCollector.recordLatency(anyString(), anyObject(SortedMap.class), anyLong());
        metricCollector.recordLatency(anyString(), anyObject(SortedMap.class), anyLong());
        metricCollector.recordCounterValue(anyString(), anyObject(SortedMap.class), anyInt());
        targetGroupLBMapProvider.handleMissingTgs(ImmutableSet.of(targetGroupResource2));
        replayAll();
        assertEquals(
                ImmutableSet.of(ResourceRelation.builder()
                        .from(lbResource)
                        .to(lambdaResource)
                        .name("ROUTES_TO")
                        .build()),
                testClass.getRoutings()
        );
        verifyAll();
    }
}
