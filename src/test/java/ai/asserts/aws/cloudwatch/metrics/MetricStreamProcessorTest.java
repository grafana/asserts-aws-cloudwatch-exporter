/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.cloudwatch.metrics;

import ai.asserts.aws.AWSClientProvider;
import ai.asserts.aws.MetricNameUtil;
import ai.asserts.aws.ObjectMapperFactory;
import ai.asserts.aws.RateLimiter;
import ai.asserts.aws.cloudwatch.config.ScrapeConfigProvider;
import ai.asserts.aws.exporter.BasicMetricCollector;
import ai.asserts.aws.exporter.LabelBuilder;
import ai.asserts.aws.exporter.MetricSampleBuilder;
import ai.asserts.aws.exporter.OpenTelemetryMetricConverter;
import ai.asserts.aws.lambda.LambdaLabelConverter;
import com.google.common.collect.ImmutableList;
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest;
import io.prometheus.client.Collector;
import io.prometheus.client.CollectorRegistry;
import org.easymock.EasyMockSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.S3Client;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.List;
import java.util.SortedMap;

import static org.easymock.EasyMock.anyLong;
import static org.easymock.EasyMock.anyObject;
import static org.easymock.EasyMock.eq;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.expectLastCall;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class MetricStreamProcessorTest extends EasyMockSupport {
    private MetricStreamProcessor metricStreamProcessor;
    private CollectorRegistry collectorRegistry;
    private OpenTelemetryMetricConverter metricConverter;
    private static ExportMetricsServiceRequest request;
    private BasicMetricCollector metricCollector;
    private Collector.MetricFamilySamples metricFamilySamples;

    @BeforeAll
    public static void setupRequest() throws IOException {
        File file = new File("src/test/resources/2022-01-24-07-20-25-714017c7-169f-4447-888b-0a9543548ed6");
        FileInputStream fis = new FileInputStream(file);
        request = ExportMetricsServiceRequest.parseDelimitedFrom(fis);
    }

    @BeforeEach
    public void setup() {
        metricFamilySamples = mock(Collector.MetricFamilySamples.class);
        collectorRegistry = mock(CollectorRegistry.class);
        metricConverter = mock(OpenTelemetryMetricConverter.class);
        metricCollector = mock(BasicMetricCollector.class);
        metricStreamProcessor = new MetricStreamProcessor(collectorRegistry, metricConverter);
        metricStreamProcessor.setCollectorRegistry(collectorRegistry);
    }

    @Test
    public void afterPropertiesSet() {
        collectorRegistry.register(metricStreamProcessor);
        replayAll();
        metricStreamProcessor.afterPropertiesSet();
        verifyAll();
    }

    @Test
    public void receiveMetrics() {
        expect(metricConverter.buildSamplesFromOT(request)).andReturn(
                ImmutableList.of(metricFamilySamples));
        replayAll();
        metricStreamProcessor.process(request);
        assertEquals(ImmutableList.of(metricFamilySamples), metricStreamProcessor.collect());
        assertEquals(ImmutableList.of(), metricStreamProcessor.collect());
        verifyAll();
    }

    @Test
    public void integrationTest() {
        AWSClientProvider awsClientProvider = mock(AWSClientProvider.class);
        S3Client s3Client = mock(S3Client.class);

        expect(awsClientProvider.getS3Client()).andReturn(s3Client).anyTimes();
        metricCollector.recordHistogram(
                eq("aws_metric_delivery_latency_milliseconds"), anyObject(SortedMap.class), anyLong());
        expectLastCall().anyTimes();
        replayAll();

        ScrapeConfigProvider scrapeConfigProvider = new ScrapeConfigProvider(
                new ObjectMapperFactory(),
                awsClientProvider, new RateLimiter(metricCollector),
                "cloudwatch_scrape_config.yml"
        );
        MetricNameUtil metricNameUtil = new MetricNameUtil(scrapeConfigProvider);
        LambdaLabelConverter lambdaLabelConverter = new LambdaLabelConverter(metricNameUtil);
        LabelBuilder labelBuilder = new LabelBuilder(scrapeConfigProvider, metricNameUtil, lambdaLabelConverter);
        MetricSampleBuilder sampleBuilder = new MetricSampleBuilder(metricNameUtil, labelBuilder);
        metricStreamProcessor = new MetricStreamProcessor(new CollectorRegistry(),
                new OpenTelemetryMetricConverter(metricNameUtil, sampleBuilder, scrapeConfigProvider, metricCollector));
        metricStreamProcessor.process(request);
        List<Collector.MetricFamilySamples> metricFamilySamples = metricStreamProcessor.collect();

        verifyAll();
        assertTrue(metricFamilySamples.size() > 0);
    }
}
