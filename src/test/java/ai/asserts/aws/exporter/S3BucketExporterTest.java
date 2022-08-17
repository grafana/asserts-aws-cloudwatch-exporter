/*
 *  Copyright © 2022.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.exporter;

import ai.asserts.aws.AWSClientProvider;
import ai.asserts.aws.AccountProvider;
import ai.asserts.aws.RateLimiter;
import ai.asserts.aws.TagUtil;
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
import software.amazon.awssdk.services.resourcegroupstaggingapi.model.Tag;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.Bucket;
import software.amazon.awssdk.services.s3.model.ListBucketsResponse;

import java.util.SortedMap;
import java.util.TreeMap;

import static ai.asserts.aws.MetricNameUtil.SCRAPE_ACCOUNT_ID_LABEL;
import static org.easymock.EasyMock.anyObject;
import static org.easymock.EasyMock.capture;
import static org.easymock.EasyMock.eq;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.expectLastCall;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class S3BucketExporterTest extends EasyMockSupport {

    public CollectorRegistry collectorRegistry;
    private AccountProvider.AWSAccount accountRegion;
    private AWSClientProvider awsClientProvider;
    private RateLimiter rateLimiter;
    private MetricSampleBuilder sampleBuilder;
    private Collector.MetricFamilySamples.Sample sample;
    private Collector.MetricFamilySamples familySamples;
    private S3Client s3Client;
    private ResourceTagHelper resourceTagHelper;
    private TagUtil tagUtil;
    private S3BucketExporter testClass;

    @BeforeEach
    public void setup() {
        accountRegion = new AccountProvider.AWSAccount("account1", "", "",
                "role", ImmutableSet.of("region1"));
        AccountProvider accountProvider = mock(AccountProvider.class);
        sampleBuilder = mock(MetricSampleBuilder.class);
        sample = mock(Collector.MetricFamilySamples.Sample.class);
        familySamples = mock(Collector.MetricFamilySamples.class);
        awsClientProvider = mock(AWSClientProvider.class);
        rateLimiter = mock(RateLimiter.class);
        collectorRegistry = mock(CollectorRegistry.class);
        s3Client = mock(S3Client.class);
        resourceTagHelper = mock(ResourceTagHelper.class);
        tagUtil = mock(TagUtil.class);
        expect(accountProvider.getAccounts()).andReturn(ImmutableSet.of(accountRegion));
        testClass = new S3BucketExporter(accountProvider, awsClientProvider, collectorRegistry, rateLimiter,
                sampleBuilder, resourceTagHelper, tagUtil);
    }

    @Test
    public void exporterBucketTest() {

        SortedMap<String, String> labels1 = new TreeMap<>();
        labels1.put("namespace", "AWS/S3");
        labels1.put("region", "region1");
        labels1.put("name", "b1");
        labels1.put("id", "b1");
        labels1.put("job", "b1");
        labels1.put("tag_k", "v");
        labels1.put(SCRAPE_ACCOUNT_ID_LABEL, "account1");
        labels1.put("aws_resource_type", "AWS::S3::Bucket");
        ListBucketsResponse response = ListBucketsResponse
                .builder()
                .buckets(Bucket.builder().name("b1").build())
                .build();
        Capture<RateLimiter.AWSAPICall<ListBucketsResponse>> callbackCapture = Capture.newInstance();

        expect(rateLimiter.doWithRateLimit(eq("S3Client/listBuckets"),
                anyObject(SortedMap.class), capture(callbackCapture))).andReturn(response);
        expect(awsClientProvider.getS3Client("region1", accountRegion)).andReturn(s3Client);
        ImmutableList<Tag> tags = ImmutableList.of(Tag.builder().key("k").value("v").build());
        expect(resourceTagHelper.getResourcesWithTag(accountRegion, "region1", "s3:bucket", ImmutableList.of(
                "b1")))
                .andReturn(ImmutableMap.of("b1", Resource.builder()
                        .tags(tags)
                        .build()));
        expect(tagUtil.tagLabels(tags)).andReturn(ImmutableMap.of("tag_k", "v"));
        expect(sampleBuilder.buildSingleSample("aws_resource", labels1, 1.0D))
                .andReturn(sample);
        expect(sampleBuilder.buildFamily(ImmutableList.of(sample))).andReturn(familySamples);
        expectLastCall();
        replayAll();
        testClass.update();
        testClass.collect();
        assertEquals(ImmutableList.of(familySamples), testClass.collect());
        verifyAll();
    }
}
