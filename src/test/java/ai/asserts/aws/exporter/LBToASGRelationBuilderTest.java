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
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import org.easymock.EasyMockSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.autoscaling.AutoScalingClient;
import software.amazon.awssdk.services.autoscaling.model.AutoScalingGroup;
import software.amazon.awssdk.services.autoscaling.model.DescribeAutoScalingGroupsResponse;

import java.util.Optional;
import java.util.SortedMap;

import static ai.asserts.aws.MetricNameUtil.SCRAPE_LATENCY_METRIC;
import static org.easymock.EasyMock.anyLong;
import static org.easymock.EasyMock.anyObject;
import static org.easymock.EasyMock.eq;
import static org.easymock.EasyMock.expect;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class LBToASGRelationBuilderTest extends EasyMockSupport {
    private AWSClientProvider awsClientProvider;
    private AutoScalingClient autoScalingClient;
    private Resource asgResource;
    private Resource tgResource;
    private Resource lbResource;
    private ResourceMapper resourceMapper;
    private TargetGroupLBMapProvider targetGroupLBMapProvider;
    private BasicMetricCollector metricCollector;
    private LBToASGRelationBuilder testClass;

    @BeforeEach
    public void setup() {
        ScrapeConfig scrapeConfig = mock(ScrapeConfig.class);
        ScrapeConfigProvider scrapeConfigProvider = mock(ScrapeConfigProvider.class);
        awsClientProvider = mock(AWSClientProvider.class);
        autoScalingClient = mock(AutoScalingClient.class);
        targetGroupLBMapProvider = mock(TargetGroupLBMapProvider.class);
        tgResource = mock(Resource.class);
        asgResource = mock(Resource.class);
        lbResource = mock(Resource.class);
        resourceMapper = mock(ResourceMapper.class);
        metricCollector = mock(BasicMetricCollector.class);
        testClass = new LBToASGRelationBuilder(scrapeConfigProvider, awsClientProvider, resourceMapper,
                targetGroupLBMapProvider, new RateLimiter(metricCollector));

        expect(scrapeConfigProvider.getScrapeConfig()).andReturn(scrapeConfig).anyTimes();
        expect(scrapeConfig.getRegions()).andReturn(ImmutableSet.of("region"));
    }

    @Test
    void updateRouting() {
        expect(awsClientProvider.getAutoScalingClient("region")).andReturn(autoScalingClient);
        expect(autoScalingClient.describeAutoScalingGroups()).andReturn(DescribeAutoScalingGroupsResponse.builder()
                .autoScalingGroups(AutoScalingGroup.builder()
                        .autoScalingGroupARN("asg-arn")
                        .targetGroupARNs("tg-arn")
                        .build())
                .build());

        metricCollector.recordLatency(eq(SCRAPE_LATENCY_METRIC), anyObject(SortedMap.class), anyLong());

        expect(resourceMapper.map("tg-arn")).andReturn(Optional.of(tgResource));
        expect(resourceMapper.map("asg-arn")).andReturn(Optional.of(asgResource));
        expect(targetGroupLBMapProvider.getTgToLB()).andReturn(ImmutableMap.of(tgResource, lbResource)).anyTimes();
        autoScalingClient.close();

        replayAll();
        testClass.updateRouting();
        assertEquals(ImmutableSet.of(
                ResourceRelation.builder()
                        .from(lbResource)
                        .to(asgResource)
                        .name("ROUTES_TO")
                        .build()
        ), testClass.getRoutingConfigs());
        verifyAll();
    }
}
