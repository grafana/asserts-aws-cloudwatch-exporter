/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.exporter;

import ai.asserts.aws.AWSClientProvider;
import ai.asserts.aws.RateLimiter;
import ai.asserts.aws.cloudwatch.config.ScrapeConfig;
import ai.asserts.aws.cloudwatch.config.ScrapeConfigProvider;
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
import software.amazon.awssdk.services.elasticloadbalancingv2.model.TargetHealthDescription;

import java.util.Optional;
import java.util.SortedMap;

import static org.easymock.EasyMock.anyLong;
import static org.easymock.EasyMock.anyObject;
import static org.easymock.EasyMock.anyString;
import static org.easymock.EasyMock.expect;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class LBToLambdaRoutingBuilderTest extends EasyMockSupport {
    private ElasticLoadBalancingV2Client elbV2Client;
    private BasicMetricCollector metricCollector;
    private ResourceMapper resourceMapper;
    private Resource targetResource;
    private Resource lbRsource;
    private Resource lambdaResource;
    private LBToLambdaRoutingBuilder testClass;

    @BeforeEach
    public void setup() {
        AccountIDProvider accountIDProvider = mock(AccountIDProvider.class);
        ScrapeConfigProvider scrapeConfigProvider = mock(ScrapeConfigProvider.class);
        ScrapeConfig scrapeConfig = mock(ScrapeConfig.class);
        AWSClientProvider awsClientProvider = mock(AWSClientProvider.class);
        TargetGroupLBMapProvider targetGroupLBMapProvider = mock(TargetGroupLBMapProvider.class);

        metricCollector = mock(BasicMetricCollector.class);
        elbV2Client = mock(ElasticLoadBalancingV2Client.class);
        resourceMapper = mock(ResourceMapper.class);

        targetResource = mock(Resource.class);
        lbRsource = mock(Resource.class);
        lambdaResource = mock(Resource.class);

        testClass = new LBToLambdaRoutingBuilder(accountIDProvider, scrapeConfigProvider, awsClientProvider,
                new RateLimiter(metricCollector), resourceMapper, targetGroupLBMapProvider);


        expect(accountIDProvider.getAccountId()).andReturn("account").anyTimes();
        expect(scrapeConfigProvider.getScrapeConfig()).andReturn(scrapeConfig).anyTimes();
        expect(scrapeConfig.getRegions()).andReturn(ImmutableSet.of("region")).anyTimes();
        expect(awsClientProvider.getELBV2Client("region")).andReturn(elbV2Client).anyTimes();
        expect(targetGroupLBMapProvider.getTgToLB()).andReturn(ImmutableMap.of(targetResource, lbRsource));
    }

    @Test
    void getRoutings() {
        expect(targetResource.getArn()).andReturn("tg-arn");
        expect(elbV2Client.describeTargetHealth(DescribeTargetHealthRequest.builder()
                .targetGroupArn("tg-arn")
                .build())).andReturn(DescribeTargetHealthResponse.builder()
                .targetHealthDescriptions(TargetHealthDescription.builder()
                        .target(TargetDescription.builder()
                                .id("lambda-arn")
                                .build())
                        .build())
                .build());
        expect(resourceMapper.map("lambda-arn")).andReturn(Optional.of(lambdaResource));
        expect(lambdaResource.getType()).andReturn(ResourceType.LambdaFunction).anyTimes();

        metricCollector.recordLatency(anyString(), anyObject(SortedMap.class), anyLong());
        elbV2Client.close();

        replayAll();
        assertEquals(
                ImmutableSet.of(ResourceRelation.builder()
                        .from(lbRsource)
                        .to(lambdaResource)
                        .name("ROUTES_TO")
                        .build()),
                testClass.getRoutings()
        );
        verifyAll();
    }
}
