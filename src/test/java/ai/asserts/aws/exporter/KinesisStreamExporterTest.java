/*
 *  Copyright © 2022.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.exporter;

import ai.asserts.aws.AWSClientProvider;
import ai.asserts.aws.ScrapeConfigProvider;
import ai.asserts.aws.TaskExecutorUtil;
import ai.asserts.aws.TestTaskThreadPool;
import ai.asserts.aws.account.AWSAccount;
import ai.asserts.aws.account.AccountProvider;
import ai.asserts.aws.AWSApiCallRateLimiter;
import ai.asserts.aws.TagUtil;
import ai.asserts.aws.config.ScrapeConfig;
import ai.asserts.aws.resource.Resource;
import ai.asserts.aws.resource.ResourceTagHelper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import io.prometheus.client.Collector;
import io.prometheus.client.CollectorRegistry;
import org.easymock.Capture;
import org.easymock.EasyMockSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.kinesis.KinesisClient;
import software.amazon.awssdk.services.kinesis.model.ListStreamsResponse;
import software.amazon.awssdk.services.resourcegroupstaggingapi.model.Tag;

import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;

import static ai.asserts.aws.MetricNameUtil.SCRAPE_ACCOUNT_ID_LABEL;
import static org.easymock.EasyMock.anyObject;
import static org.easymock.EasyMock.capture;
import static org.easymock.EasyMock.eq;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.expectLastCall;
import static org.junit.jupiter.api.Assertions.assertEquals;

@SuppressWarnings("unchecked")
public class KinesisStreamExporterTest extends EasyMockSupport {

    public CollectorRegistry collectorRegistry;
    private AWSAccount accountRegion;
    private AWSClientProvider awsClientProvider;
    private AWSApiCallRateLimiter rateLimiter;
    private MetricSampleBuilder sampleBuilder;
    private Collector.MetricFamilySamples.Sample sample;
    private Collector.MetricFamilySamples familySamples;
    private KinesisClient kinesisClient;
    private ResourceTagHelper resourceTagHelper;
    private BasicMetricCollector basicMetricCollector;
    private TagUtil tagUtil;
    private ScrapeConfigProvider scrapeConfigProvider;
    private ScrapeConfig scrapeConfig;
    private KinesisStreamExporter testClass;

    @BeforeEach
    public void setup() {
        accountRegion = new AWSAccount("tenant", "account1", "", "",
                "role", ImmutableSet.of("region1"));
        AccountProvider accountProvider = mock(AccountProvider.class);
        sampleBuilder = mock(MetricSampleBuilder.class);
        sample = mock(Collector.MetricFamilySamples.Sample.class);
        familySamples = mock(Collector.MetricFamilySamples.class);
        awsClientProvider = mock(AWSClientProvider.class);
        rateLimiter = mock(AWSApiCallRateLimiter.class);
        collectorRegistry = mock(CollectorRegistry.class);
        kinesisClient = mock(KinesisClient.class);
        resourceTagHelper = mock(ResourceTagHelper.class);
        tagUtil = mock(TagUtil.class);
        scrapeConfigProvider = mock(ScrapeConfigProvider.class);
        scrapeConfig = mock(ScrapeConfig.class);
        expect(accountProvider.getAccounts()).andReturn(ImmutableSet.of(accountRegion));
        testClass = new KinesisStreamExporter(accountProvider, awsClientProvider, collectorRegistry, rateLimiter,
                sampleBuilder, resourceTagHelper, tagUtil, new TaskExecutorUtil(new TestTaskThreadPool(),
                new AWSApiCallRateLimiter(basicMetricCollector, (account) -> "tenant")), scrapeConfigProvider);
    }

    @Test
    public void exporterStreamTest() {

        SortedMap<String, String> labels1 = new TreeMap<>();
        labels1.put("region", "region1");
        labels1.put("name", "stream1");
        labels1.put("id", "stream1");
        labels1.put("job", "stream1");
        labels1.put("namespace", "AWS/Kinesis");
        labels1.put("tag_k", "v");
        labels1.put(SCRAPE_ACCOUNT_ID_LABEL, "account1");
        labels1.put("aws_resource_type", "AWS::Kinesis::Stream");
        ListStreamsResponse response = ListStreamsResponse
                .builder()
                .streamNames("stream1")
                .build();
        Capture<AWSApiCallRateLimiter.AWSAPICall<ListStreamsResponse>> callbackCapture = Capture.newInstance();

        expect(scrapeConfigProvider.getScrapeConfig("tenant")).andReturn(scrapeConfig);
        expect(rateLimiter.doWithRateLimit(eq("KinesisClient/listStreams"),
                anyObject(SortedMap.class), capture(callbackCapture))).andReturn(response);
        expect(awsClientProvider.getKinesisClient("region1", accountRegion)).andReturn(kinesisClient);
        ImmutableList<Tag> tags = ImmutableList.of(Tag.builder().build());
        expect(resourceTagHelper.getResourcesWithTag(accountRegion, "region1", "kinesis:stream",
                ImmutableList.of("stream1"))).andReturn(ImmutableMap.of("stream1", Resource.builder()
                .tags(tags)
                .build()));
        expect(tagUtil.tagLabels(scrapeConfig, tags)).andReturn(ImmutableMap.of("tag_k", "v"));
        expect(sampleBuilder.buildSingleSample("aws_resource", labels1, 1.0D))
                .andReturn(Optional.of(sample));
        expect(sampleBuilder.buildFamily(ImmutableList.of(sample))).andReturn(Optional.of(familySamples));
        expectLastCall();
        replayAll();
        testClass.update();
        testClass.collect();
        assertEquals(ImmutableList.of(familySamples), testClass.collect());
        verifyAll();
    }
}
