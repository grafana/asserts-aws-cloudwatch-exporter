/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.exporter;

import ai.asserts.aws.AWSClientProvider;
import ai.asserts.aws.RateLimiter;
import ai.asserts.aws.cloudwatch.config.ScrapeConfig;
import ai.asserts.aws.cloudwatch.config.ScrapeConfigProvider;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import io.prometheus.client.Collector;
import org.easymock.Capture;
import org.easymock.EasyMockSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.config.ConfigClient;
import software.amazon.awssdk.services.config.model.ListDiscoveredResourcesRequest;
import software.amazon.awssdk.services.config.model.ListDiscoveredResourcesResponse;
import software.amazon.awssdk.services.config.model.ResourceIdentifier;

import java.util.SortedMap;

import static ai.asserts.aws.MetricNameUtil.SCRAPE_REGION_LABEL;
import static org.easymock.EasyMock.anyObject;
import static org.easymock.EasyMock.capture;
import static org.easymock.EasyMock.eq;
import static org.easymock.EasyMock.expect;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static software.amazon.awssdk.services.config.model.ResourceType.AWS_DYNAMO_DB_TABLE;
import static software.amazon.awssdk.services.config.model.ResourceType.AWS_S3_BUCKET;

public class ResourceExporterTest extends EasyMockSupport {
    private ScrapeConfigProvider scrapeConfigProvider;
    private ScrapeConfig scrapeConfig;
    private AWSClientProvider awsClientProvider;
    private ConfigClient configClient;
    private RateLimiter rateLimiter;
    private Collector.MetricFamilySamples.Sample sample;
    private Collector.MetricFamilySamples metricFamilySamples;
    private MetricSampleBuilder metricSampleBuilder;
    private ResourceExporter testClass;

    @BeforeEach
    public void setup() {
        scrapeConfigProvider = mock(ScrapeConfigProvider.class);
        scrapeConfig = mock(ScrapeConfig.class);
        awsClientProvider = mock(AWSClientProvider.class);
        configClient = mock(ConfigClient.class);
        rateLimiter = mock(RateLimiter.class);
        metricSampleBuilder = mock(MetricSampleBuilder.class);
        sample = mock(Collector.MetricFamilySamples.Sample.class);
        metricFamilySamples = mock(Collector.MetricFamilySamples.class);
        testClass = new ResourceExporter(scrapeConfigProvider, awsClientProvider, rateLimiter, metricSampleBuilder);
    }

    @Test
    public void update() {
        expect(scrapeConfigProvider.getScrapeConfig()).andReturn(scrapeConfig);
        expect(scrapeConfig.getDiscoverResourceTypes()).andReturn(ImmutableSet.of("type1")).anyTimes();
        expect(scrapeConfig.getRegions()).andReturn(ImmutableSet.of("region")).anyTimes();
        expect(awsClientProvider.getConfigClient("region")).andReturn(configClient).anyTimes();

        Capture<RateLimiter.AWSAPICall<ListDiscoveredResourcesResponse>> callbackCapture = Capture.newInstance();

        ListDiscoveredResourcesResponse response = ListDiscoveredResourcesResponse.builder()
                .resourceIdentifiers(
                        ResourceIdentifier.builder()
                                .resourceName("TableName")
                                .resourceType(AWS_DYNAMO_DB_TABLE)
                                .build(),
                        ResourceIdentifier.builder()
                                .resourceName("BucketName")
                                .resourceType(AWS_S3_BUCKET)
                                .build())
                .build();

        expect(configClient.listDiscoveredResources(ListDiscoveredResourcesRequest.builder()
                .includeDeletedResources(false)
                .resourceType("type1")
                .build())).andReturn(response);

        expect(rateLimiter.doWithRateLimit(eq("ConfigClient/listDiscoveredResources"),
                anyObject(SortedMap.class), capture(callbackCapture))).andReturn(response);

        expect(metricSampleBuilder.buildSingleSample("aws_resource",
                ImmutableSortedMap.of(
                        SCRAPE_REGION_LABEL, "region",
                        "aws_resource_type", "DynamoDBTable",
                        "name", "TableName",
                        "job", "TableName"
                ),
                1.0D))
                .andReturn(sample);
        expect(metricSampleBuilder.buildSingleSample("aws_resource",
                ImmutableSortedMap.of(
                        SCRAPE_REGION_LABEL, "region",
                        "aws_resource_type", "S3Bucket",
                        "name", "BucketName",
                        "job", "BucketName"
                ),
                1.0D))
                .andReturn(sample);

        expect(metricSampleBuilder.buildFamily(ImmutableList.of(sample, sample))).andReturn(metricFamilySamples);

        replayAll();
        testClass.update();

        assertEquals(response, callbackCapture.getValue().makeCall());

        assertEquals(ImmutableList.of(metricFamilySamples), testClass.collect());
        verifyAll();
    }
}
