/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.cloudwatch;

import ai.asserts.aws.MetricNameUtil;
import ai.asserts.aws.ObjectMapperFactory;
import ai.asserts.aws.cloudwatch.config.ScrapeConfigProvider;
import ai.asserts.aws.controller.MetricStreamController;
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

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.List;

import static org.easymock.EasyMock.expect;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class MetricStreamControllerTest extends EasyMockSupport {
    private MetricStreamController metricStreamController;
    private CollectorRegistry collectorRegistry;
    private OpenTelemetryMetricConverter metricConverter;
    private static ExportMetricsServiceRequest request;
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
        metricStreamController = new MetricStreamController(collectorRegistry, metricConverter);
        metricStreamController.setCollectorRegistry(collectorRegistry);
    }

    @Test
    public void afterPropertiesSet() {
        collectorRegistry.register(metricStreamController);
        replayAll();
        metricStreamController.afterPropertiesSet();
        verifyAll();
    }

    @Test
    public void receiveMetrics() {
        expect(metricConverter.buildSamplesFromOT(request)).andReturn(
                ImmutableList.of(metricFamilySamples));
        replayAll();
        metricStreamController.receiveMetrics(request);
        assertEquals(ImmutableList.of(metricFamilySamples), metricStreamController.collect());
        assertEquals(ImmutableList.of(), metricStreamController.collect());
        verifyAll();
    }

    @Test
    public void integrationTest() {
        ScrapeConfigProvider scrapeConfigProvider = new ScrapeConfigProvider(
                new ObjectMapperFactory(), "cloudwatch_scrape_config.yml"
        );
        MetricNameUtil metricNameUtil = new MetricNameUtil(scrapeConfigProvider);
        LambdaLabelConverter lambdaLabelConverter = new LambdaLabelConverter(metricNameUtil);
        LabelBuilder labelBuilder = new LabelBuilder(scrapeConfigProvider, metricNameUtil, lambdaLabelConverter);
        MetricSampleBuilder sampleBuilder = new MetricSampleBuilder(metricNameUtil, labelBuilder);
        metricStreamController = new MetricStreamController(new CollectorRegistry(),
                new OpenTelemetryMetricConverter(metricNameUtil, sampleBuilder, scrapeConfigProvider));
        metricStreamController.receiveMetrics(request);
        List<Collector.MetricFamilySamples> metricFamilySamples = metricStreamController.collect();
        assertTrue(metricFamilySamples.size() > 0);
    }
}
