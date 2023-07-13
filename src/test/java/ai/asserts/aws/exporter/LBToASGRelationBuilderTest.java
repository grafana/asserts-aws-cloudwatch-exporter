/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.exporter;

import ai.asserts.aws.AWSClientProvider;
import ai.asserts.aws.ScrapeConfigProvider;
import ai.asserts.aws.TaskExecutorUtil;
import ai.asserts.aws.TestTaskThreadPool;
import ai.asserts.aws.account.AccountProvider;
import ai.asserts.aws.account.AWSAccount;
import ai.asserts.aws.AWSApiCallRateLimiter;
import ai.asserts.aws.TagUtil;
import ai.asserts.aws.config.ScrapeConfig;
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
import software.amazon.awssdk.services.autoscaling.model.TagDescription;
import software.amazon.awssdk.services.resourcegroupstaggingapi.model.Tag;

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

@SuppressWarnings("unchecked")
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
    private TagUtil tagUtil;
    private ScrapeConfigProvider scrapeConfigProvider;
    private ScrapeConfig scrapeConfig;
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
        CollectorRegistry collectorRegistry = mock(CollectorRegistry.class);
        metricFamilySamples = mock(MetricFamilySamples.class);
        sample = mock(Sample.class);
        tagUtil = mock(TagUtil.class);
        scrapeConfigProvider = mock(ScrapeConfigProvider.class);
        scrapeConfig = mock(ScrapeConfig.class);
        AWSApiCallRateLimiter rateLimiter = new AWSApiCallRateLimiter(metricCollector, (account) -> "tenant");
        testClass = new LBToASGRelationBuilder(awsClientProvider, resourceMapper,
                targetGroupLBMapProvider, rateLimiter,
                accountProvider, metricSampleBuilder, collectorRegistry, tagUtil,
                new TaskExecutorUtil(new TestTaskThreadPool(), rateLimiter), scrapeConfigProvider);
    }

    @Test
    void updateRouting() {
        AWSAccount awsAccount =
                new AWSAccount("tenant", "123123123", "accessId", "secretKey", "role", ImmutableSet.of("region"));
        expect(accountProvider.getAccounts()).andReturn(ImmutableSet.of(awsAccount));
        expect(scrapeConfigProvider.getScrapeConfig("tenant")).andReturn(scrapeConfig);
        expect(awsClientProvider.getAutoScalingClient("region", awsAccount)).andReturn(autoScalingClient);
        expect(autoScalingClient.describeAutoScalingGroups()).andReturn(DescribeAutoScalingGroupsResponse.builder()
                .autoScalingGroups(AutoScalingGroup.builder()
                        .autoScalingGroupARN("asg-arn")
                        .targetGroupARNs("tg-arn")
                        .autoScalingGroupName("group")
                        .build())
                .build());

        expect(autoScalingClient.describeTags()).andReturn(DescribeTagsResponse.builder()
                .tags(ImmutableList.of(TagDescription.builder()
                        .key("k").value("v")
                        .resourceType("auto-scaling-group")
                        .resourceId("group")
                        .build()))
                .build());

        metricCollector.recordLatency(eq(SCRAPE_LATENCY_METRIC), anyObject(SortedMap.class), anyLong());
        metricCollector.recordLatency(eq(SCRAPE_LATENCY_METRIC), anyObject(SortedMap.class), anyLong());

        expect(asgResource.getName()).andReturn("asg-name").times(2);
        expect(asgResource.getId()).andReturn("asg-id");

        expect(tagUtil.tagLabels(scrapeConfig, ImmutableList.of(Tag.builder()
                .key("k").value("v")
                .build()))).andReturn(ImmutableMap.of("tag_k", "v"));

        expect(metricSampleBuilder.buildSingleSample("aws_resource",
                new ImmutableMap.Builder<String, String>()
                        .put(SCRAPE_ACCOUNT_ID_LABEL, "123123123")
                        .put(SCRAPE_REGION_LABEL, "region")
                        .put("namespace", "AWS/AutoScaling")
                        .put("aws_resource_type", "AWS::AutoScaling::AutoScalingGroup")
                        .put("job", "asg-name")
                        .put("name", "asg-name")
                        .put("id", "asg-id")
                        .put("tag_k", "v")
                        .build(), 1.0D)).andReturn(Optional.of(sample));

        expect(metricSampleBuilder.buildFamily(ImmutableList.of(sample))).andReturn(Optional.of(metricFamilySamples));

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
