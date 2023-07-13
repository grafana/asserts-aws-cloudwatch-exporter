/*
 *  Copyright © 2022.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.exporter;

import ai.asserts.aws.AWSClientProvider;
import ai.asserts.aws.AWSApiCallRateLimiter;
import ai.asserts.aws.ScrapeConfigProvider;
import ai.asserts.aws.TagUtil;
import ai.asserts.aws.TaskExecutorUtil;
import ai.asserts.aws.TestTaskThreadPool;
import ai.asserts.aws.account.AWSAccount;
import ai.asserts.aws.account.AccountProvider;
import ai.asserts.aws.config.ScrapeConfig;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import io.prometheus.client.Collector;
import io.prometheus.client.CollectorRegistry;
import org.easymock.EasyMockSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.redshift.RedshiftClient;
import software.amazon.awssdk.services.redshift.model.Cluster;
import software.amazon.awssdk.services.redshift.model.DescribeClustersResponse;
import software.amazon.awssdk.services.redshift.model.Tag;
import software.amazon.awssdk.utils.ImmutableMap;

import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;

import static ai.asserts.aws.MetricNameUtil.SCRAPE_ACCOUNT_ID_LABEL;
import static ai.asserts.aws.MetricNameUtil.SCRAPE_LATENCY_METRIC;
import static org.easymock.EasyMock.anyLong;
import static org.easymock.EasyMock.anyObject;
import static org.easymock.EasyMock.eq;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.expectLastCall;
import static org.junit.jupiter.api.Assertions.assertEquals;

@SuppressWarnings("unchecked")
public class RedshiftExporterTest extends EasyMockSupport {

    public CollectorRegistry collectorRegistry;
    private AWSAccount accountRegion;
    private AWSClientProvider awsClientProvider;
    private MetricSampleBuilder sampleBuilder;
    private Collector.MetricFamilySamples.Sample sample;
    private Collector.MetricFamilySamples familySamples;
    private RedshiftClient redshiftClient;
    private TagUtil tagUtil;
    private BasicMetricCollector metricCollector;
    private ScrapeConfigProvider scrapeConfigProvider;
    private ScrapeConfig scrapeConfig;
    private RedshiftExporter testClass;

    @BeforeEach
    public void setup() {
        accountRegion = new AWSAccount("tenant", "account1", "", "",
                "role", ImmutableSet.of("region1"));
        AccountProvider accountProvider = mock(AccountProvider.class);
        sampleBuilder = mock(MetricSampleBuilder.class);
        sample = mock(Collector.MetricFamilySamples.Sample.class);
        familySamples = mock(Collector.MetricFamilySamples.class);
        awsClientProvider = mock(AWSClientProvider.class);
        collectorRegistry = mock(CollectorRegistry.class);
        redshiftClient = mock(RedshiftClient.class);
        tagUtil = mock(TagUtil.class);
        metricCollector = mock(BasicMetricCollector.class);
        scrapeConfigProvider = mock(ScrapeConfigProvider.class);
        scrapeConfig = mock(ScrapeConfig.class);
        expect(accountProvider.getAccounts()).andReturn(ImmutableSet.of(accountRegion));
        AWSApiCallRateLimiter rateLimiter = new AWSApiCallRateLimiter(metricCollector, (account) -> "tenant");
        testClass = new RedshiftExporter(accountProvider, awsClientProvider, collectorRegistry, rateLimiter,
                sampleBuilder, tagUtil, new TaskExecutorUtil(new TestTaskThreadPool(), rateLimiter),
                scrapeConfigProvider);
    }

    @Test
    public void exporterClusterTest() {
        expect(scrapeConfigProvider.getScrapeConfig("tenant")).andReturn(scrapeConfig);
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
        expect(awsClientProvider.getRedshiftClient("region1", accountRegion)).andReturn(redshiftClient);
        expect(redshiftClient.describeClusters()).andReturn(response);
        metricCollector.recordLatency(eq(SCRAPE_LATENCY_METRIC), anyObject(SortedMap.class), anyLong());
        expect(tagUtil.tagLabels(
                scrapeConfig,
                ImmutableList.of(software.amazon.awssdk.services.resourcegroupstaggingapi.model.Tag.builder()
                        .key("k").value("v")
                        .build()))).andReturn(ImmutableMap.of("tag_k", "v"));
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
