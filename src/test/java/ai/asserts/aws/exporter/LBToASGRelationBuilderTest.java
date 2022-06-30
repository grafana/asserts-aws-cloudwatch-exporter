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
import ai.asserts.aws.resource.ResourceRelation;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import io.prometheus.client.Collector.MetricFamilySamples;
import io.prometheus.client.Collector.MetricFamilySamples.Sample;
import io.prometheus.client.CollectorRegistry;
import org.easymock.EasyMockSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.autoscaling.AutoScalingClient;
import software.amazon.awssdk.services.autoscaling.model.AutoScalingGroup;
import software.amazon.awssdk.services.autoscaling.model.DescribeAutoScalingGroupsResponse;
import software.amazon.awssdk.services.autoscaling.model.DescribeTagsResponse;

import java.util.Collections;
import java.util.Optional;
import java.util.SortedMap;

import static ai.asserts.aws.MetricNameUtil.SCRAPE_ACCOUNT_ID_LABEL;
import static ai.asserts.aws.MetricNameUtil.SCRAPE_LATENCY_METRIC;
import static ai.asserts.aws.MetricNameUtil.SCRAPE_REGION_LABEL;
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
    private AccountProvider accountProvider;
    private MetricSampleBuilder metricSampleBuilder;
    private MetricFamilySamples metricFamilySamples;
    private Sample sample;
    private CollectorRegistry collectorRegistry;
    private LBToASGRelationBuilder testClass;

    @BeforeEach
    public void setup() {
        awsClientProvider = mock(AWSClientProvider.class);
        autoScalingClient = mock(AutoScalingClient.class);
        targetGroupLBMapProvider = mock(TargetGroupLBMapProvider.class);
        tgResource = mock(Resource.class);
        asgResource = mock(Resource.class);
        lbResource = mock(Resource.class);
        resourceMapper = mock(ResourceMapper.class);
        metricCollector = mock(BasicMetricCollector.class);
        accountProvider = mock(AccountProvider.class);
        metricSampleBuilder = mock(MetricSampleBuilder.class);
        collectorRegistry = mock(CollectorRegistry.class);
        metricFamilySamples = mock(MetricFamilySamples.class);
        sample = mock(Sample.class);
        testClass = new LBToASGRelationBuilder(awsClientProvider, resourceMapper,
                targetGroupLBMapProvider, new RateLimiter(metricCollector),
                accountProvider, metricSampleBuilder, collectorRegistry);
    }

    @Test
    void updateRouting() {
        AWSAccount awsAccount = new AWSAccount("123123123", "accessId", "secretKey", "role", ImmutableSet.of("region"));
        expect(accountProvider.getAccounts()).andReturn(ImmutableSet.of(awsAccount));
        expect(awsClientProvider.getAutoScalingClient("region", awsAccount)).andReturn(autoScalingClient);
        expect(autoScalingClient.describeAutoScalingGroups()).andReturn(DescribeAutoScalingGroupsResponse.builder()
                .autoScalingGroups(AutoScalingGroup.builder()
                        .autoScalingGroupARN("asg-arn")
                        .targetGroupARNs("tg-arn")
                        .build())
                .build());

        expect(autoScalingClient.describeTags()).andReturn(DescribeTagsResponse.builder()
                .tags(Collections.emptyList())
                .build());

        metricCollector.recordLatency(eq(SCRAPE_LATENCY_METRIC), anyObject(SortedMap.class), anyLong());
        metricCollector.recordLatency(eq(SCRAPE_LATENCY_METRIC), anyObject(SortedMap.class), anyLong());

        expect(asgResource.getName()).andReturn("asg-name").times(2);
        expect(asgResource.getId()).andReturn("asg-id");

        expect(metricSampleBuilder.buildSingleSample("aws_resource",
                new ImmutableMap.Builder<String, String>()
                        .put(SCRAPE_ACCOUNT_ID_LABEL, "123123123")
                        .put(SCRAPE_REGION_LABEL, "region")
                        .put("namespace", "AWS/AutoScaling")
                        .put("aws_resource_type", "AWS::AutoScaling::AutoScalingGroup")
                        .put("job", "asg-name")
                        .put("name", "asg-name")
                        .put("id", "asg-id")
                        .build(), 1.0D)).andReturn(sample);

        expect(metricSampleBuilder.buildFamily(ImmutableList.of(sample))).andReturn(metricFamilySamples);

        expect(resourceMapper.map("tg-arn")).andReturn(Optional.of(tgResource));
        expect(resourceMapper.map("asg-arn")).andReturn(Optional.of(asgResource));
        expect(targetGroupLBMapProvider.getTgToLB()).andReturn(ImmutableMap.of(tgResource, lbResource)).anyTimes();


        replayAll();
        testClass.updateRouting();
        assertEquals(ImmutableSet.of(
                ResourceRelation.builder()
                        .from(lbResource)
                        .to(asgResource)
                        .name("ROUTES_TO")
                        .build()
        ), testClass.getRoutingConfigs());
        assertEquals(ImmutableList.of(metricFamilySamples), testClass.collect());
        verifyAll();
    }
}
