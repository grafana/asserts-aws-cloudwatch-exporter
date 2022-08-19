/*
 *  Copyright © 2022.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.exporter;

import ai.asserts.aws.AWSClientProvider;
import ai.asserts.aws.AccountProvider;
import ai.asserts.aws.RateLimiter;
import ai.asserts.aws.TagUtil;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import io.prometheus.client.Collector;
import io.prometheus.client.CollectorRegistry;
import org.easymock.Capture;
import org.easymock.EasyMockSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.redshift.RedshiftClient;
import software.amazon.awssdk.services.redshift.model.Cluster;
import software.amazon.awssdk.services.redshift.model.DescribeClustersResponse;
import software.amazon.awssdk.services.redshift.model.Tag;
import software.amazon.awssdk.utils.ImmutableMap;

import java.util.SortedMap;
import java.util.TreeMap;

import static ai.asserts.aws.MetricNameUtil.SCRAPE_ACCOUNT_ID_LABEL;
import static org.easymock.EasyMock.anyObject;
import static org.easymock.EasyMock.capture;
import static org.easymock.EasyMock.eq;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.expectLastCall;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class RedshiftExporterTest extends EasyMockSupport {

    public CollectorRegistry collectorRegistry;
    private AccountProvider.AWSAccount accountRegion;
    private AWSClientProvider awsClientProvider;
    private RateLimiter rateLimiter;
    private MetricSampleBuilder sampleBuilder;
    private Collector.MetricFamilySamples.Sample sample;
    private Collector.MetricFamilySamples familySamples;
    private RedshiftClient redshiftClient;
    private TagUtil tagUtil;
    private RedshiftExporter testClass;

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
        redshiftClient = mock(RedshiftClient.class);
        tagUtil = mock(TagUtil.class);
        expect(accountProvider.getAccounts()).andReturn(ImmutableSet.of(accountRegion));
        testClass = new RedshiftExporter(accountProvider, awsClientProvider, collectorRegistry, rateLimiter,
                sampleBuilder, tagUtil);
    }

    @Test
    public void exporterClusterTest() {

        SortedMap<String, String> labels1 = new TreeMap<>();
        labels1.put("namespace", "AWS/Redshift");
        labels1.put("region", "region1");
        labels1.put("name", "cluster1");
        labels1.put("id", "cluster1");
        labels1.put("job", "cluster1");
        labels1.put("tag_k", "v");
        labels1.put(SCRAPE_ACCOUNT_ID_LABEL, "account1");
        labels1.put("aws_resource_type", "AWS::Redshift::Cluster");
        DescribeClustersResponse response = DescribeClustersResponse
                .builder()
                .clusters(Cluster.builder().clusterIdentifier("cluster1")
                        .tags(Tag.builder()
                                .key("k").value("v")
                                .build())
                        .build())
                .build();
        Capture<RateLimiter.AWSAPICall<DescribeClustersResponse>> callbackCapture = Capture.newInstance();

        expect(rateLimiter.doWithRateLimit(eq("RedshiftClient/describeClusters"),
                anyObject(SortedMap.class), capture(callbackCapture))).andReturn(response);
        expect(awsClientProvider.getRedshiftClient("region1", accountRegion)).andReturn(redshiftClient);
        expect(tagUtil.tagLabels(
                ImmutableList.of(software.amazon.awssdk.services.resourcegroupstaggingapi.model.Tag.builder()
                        .key("k").value("v")
                        .build()))).andReturn(ImmutableMap.of("tag_k", "v"));
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
