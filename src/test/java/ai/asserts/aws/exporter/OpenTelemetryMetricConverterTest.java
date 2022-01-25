/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.exporter;

import ai.asserts.aws.MetricNameUtil;
import ai.asserts.aws.cloudwatch.config.ScrapeConfigProvider;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Sets;
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest;
import io.opentelemetry.proto.common.v1.StringKeyValue;
import io.opentelemetry.proto.metrics.v1.DoubleSummary;
import io.opentelemetry.proto.metrics.v1.DoubleSummaryDataPoint;
import io.opentelemetry.proto.metrics.v1.InstrumentationLibraryMetrics;
import io.opentelemetry.proto.metrics.v1.Metric;
import io.opentelemetry.proto.metrics.v1.ResourceMetrics;
import io.opentelemetry.proto.resource.v1.Resource;
import io.prometheus.client.Collector;
import org.easymock.EasyMockSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

import static io.prometheus.client.Collector.Type.GAUGE;
import static org.easymock.EasyMock.expect;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class OpenTelemetryMetricConverterTest extends EasyMockSupport {
    private MetricNameUtil metricNameUtil;
    private MetricSampleBuilder sampleBuilder;
    private Collector.MetricFamilySamples.Sample sample;
    private ScrapeConfigProvider scrapeConfigProvider;
    private OpenTelemetryMetricConverter testClass;
    private static ExportMetricsServiceRequest request;

    @BeforeAll
    static void openTelemetryMetrics() throws IOException {
        File file = new File("src/test/resources/2022-01-24-07-20-25-714017c7-169f-4447-888b-0a9543548ed6");
        FileInputStream fis = new FileInputStream(file);
        request = ExportMetricsServiceRequest.parseDelimitedFrom(fis);
    }

    @BeforeEach
    public void setup() {
        metricNameUtil = mock(MetricNameUtil.class);
        sampleBuilder = mock(MetricSampleBuilder.class);
        sample = mock(Collector.MetricFamilySamples.Sample.class);
        scrapeConfigProvider = mock(ScrapeConfigProvider.class);
        testClass = new OpenTelemetryMetricConverter(metricNameUtil, sampleBuilder, scrapeConfigProvider);
    }

    @Test
    public void metricNameFromOTName() {
        expect(metricNameUtil.toSnakeCase("MeteredIOBytes")).andReturn("metered_io_bytes");
        replayAll();
        String otMetricName = "amazonaws.com/AWS/EFS/MeteredIOBytes";
        assertEquals("metered_io_bytes", testClass.metricNameFromOTMetricName("AWS/EFS", otMetricName));
        verifyAll();
    }

    @Test
    public void namespaceFromOTName() {
        replayAll();
        String otMetricName = "amazonaws.com/AWS/EFS/MeteredIOBytes";
        assertEquals("AWS/EFS", testClass.namespaceFromOTMetricName(otMetricName));
        verifyAll();
    }

    @Test
    public void extractAttributes() {
        assertEquals(ImmutableSortedMap.of(
                "account_id", "342994379019",
                "region", "us-west-2"
        ), testClass.extractAttributes(request.getResourceMetrics(0).getResource()));
    }

    @Test
    void fromOTDoubleSummary() {
        SortedMap<String, String> labels = new TreeMap<>();
        labels.put("provider", "aws");
        labels.put("aws_exporter_arn", "arn");
        labels.put("cw_namespace", "AWS/Lambda");
        labels.put("region", "us-west-2");

        Instant endTime = Instant.now().minusSeconds(3600);
        DoubleSummary doubleSummary = DoubleSummary.newBuilder()
                .addDataPoints(DoubleSummaryDataPoint.newBuilder()
                        .setCount(1)
                        .setSum(10.0D)
                        .setTimeUnixNano(endTime.toEpochMilli() * 1000)
                        .addQuantileValues(DoubleSummaryDataPoint.ValueAtQuantile.newBuilder()
                                .setQuantile(0)
                                .setValue(1.5D)
                                .build())
                        .addQuantileValues(DoubleSummaryDataPoint.ValueAtQuantile.newBuilder()
                                .setQuantile(1)
                                .setValue(15.0D)
                                .build())
                        .addLabels(StringKeyValue.newBuilder()
                                .setKey("DimensionName1")
                                .setValue("value11")
                                .build())
                        .addLabels(StringKeyValue.newBuilder()
                                .setKey("DimensionName2")
                                .setValue("value21")
                                .build())
                        .addLabels(StringKeyValue.newBuilder()
                                .setKey("MetricName")
                                .setValue("ReadCapacityUnits")
                                .build())
                        .build())
                .addDataPoints(DoubleSummaryDataPoint.newBuilder()
                        .setCount(2)
                        .setSum(20.0D)
                        .setTimeUnixNano(endTime.toEpochMilli() * 1000)
                        .addQuantileValues(DoubleSummaryDataPoint.ValueAtQuantile.newBuilder()
                                .setQuantile(0)
                                .setValue(3.0D)
                                .build())
                        .addQuantileValues(DoubleSummaryDataPoint.ValueAtQuantile.newBuilder()
                                .setQuantile(1)
                                .setValue(30.0D)
                                .build())
                        .addLabels(StringKeyValue.newBuilder()
                                .setKey("DimensionName1")
                                .setValue("value12")
                                .build())
                        .addLabels(StringKeyValue.newBuilder()
                                .setKey("DimensionName2")
                                .setValue("value22")
                                .build())
                        .addLabels(StringKeyValue.newBuilder()
                                .setKey("MetricName")
                                .setValue("ReadCapacityUnits")
                                .build())
                        .build())
                .build();
        String metricName = "read_capacity_units";

        expect(metricNameUtil.toSnakeCase("MetricName")).andReturn("metric_name").anyTimes();
        expect(metricNameUtil.toSnakeCase("DimensionName1")).andReturn("dim1").anyTimes();
        expect(metricNameUtil.toSnakeCase("DimensionName2")).andReturn("dim2").anyTimes();

        ImmutableSortedMap<String, String> metric1 = ImmutableSortedMap.of(
                "dim1", "value11", "dim2", "value21",
                "region", "us-west-2", "cw_namespace", "AWS/Lambda");
        expect(sampleBuilder.buildSingleSample("read_capacity_units_sum",
                metric1, 10.0D, endTime.toEpochMilli())).andReturn(sample);
        expect(sampleBuilder.buildSingleSample("read_capacity_units_count",
                metric1, 1.0D, endTime.toEpochMilli())).andReturn(sample);
        expect(sampleBuilder.buildSingleSample("read_capacity_units_min",
                metric1, 1.5D, endTime.toEpochMilli())).andReturn(sample);
        expect(sampleBuilder.buildSingleSample("read_capacity_units_max",
                metric1, 15.0D, endTime.toEpochMilli())).andReturn(sample);

        ImmutableSortedMap<String, String> metric2 = ImmutableSortedMap.of(
                "dim1", "value12", "dim2", "value22",
                "region", "us-west-2", "cw_namespace", "AWS/Lambda");
        expect(sampleBuilder.buildSingleSample("read_capacity_units_sum",
                metric2, 20.0D, endTime.toEpochMilli())).andReturn(sample);
        expect(sampleBuilder.buildSingleSample("read_capacity_units_count",
                metric2, 2.0D, endTime.toEpochMilli())).andReturn(sample);
        expect(sampleBuilder.buildSingleSample("read_capacity_units_min",
                metric2, 3.0D, endTime.toEpochMilli())).andReturn(sample);
        expect(sampleBuilder.buildSingleSample("read_capacity_units_max",
                metric2, 30.0D, endTime.toEpochMilli())).andReturn(sample);

        replayAll();
        List<Collector.MetricFamilySamples.Sample> actual = testClass.fromOTDoubleSummary(labels, metricName,
                doubleSummary);

        assertAll(
                () -> assertEquals(8, actual.size()),
                () -> assertEquals(ImmutableSet.of(sample), Sets.newHashSet(actual))
        );
        verifyAll();
    }

    @Test
    void buildFromOTMetric() {
        DoubleSummary doubleSummary = DoubleSummary.newBuilder()
                .build();
        Metric metric = Metric.newBuilder()
                .setName("amazonaws.com/AWS/DynamoDB/ReadCapacityUnits")
                .setDoubleSummary(doubleSummary)
                .build();

        expect(metricNameUtil.toSnakeCase("ReadCapacityUnits")).andReturn("read_capacity_units");

        SortedMap<String, String> labels = new TreeMap<>();
        Collector.MetricFamilySamples.Sample mockSample = new Collector.MetricFamilySamples.Sample(
                "read_capacity_units", ImmutableList.of("label"), ImmutableList.of("value"), 10.0D, 20_000L
        );

        replayAll();
        testClass = new OpenTelemetryMetricConverter(metricNameUtil, sampleBuilder, scrapeConfigProvider) {
            @Override
            List<Collector.MetricFamilySamples.Sample> fromOTDoubleSummary(SortedMap<String, String> baseLabels,
                                                                           String metricName,
                                                                           DoubleSummary actualDoubleSummary) {
                assertEquals(labels, baseLabels);
                assertEquals("read_capacity_units", metricName);
                assertEquals(doubleSummary, actualDoubleSummary);
                return ImmutableList.of(mockSample, mockSample);
            }
        };

        Map<String, List<Collector.MetricFamilySamples.Sample>> samples = new TreeMap<>();
        testClass.buildFromOTMetric(samples, labels, metric);
        assertEquals(ImmutableMap.of("cw_namespace", "AWS/DynamoDB"), labels);
        assertEquals(ImmutableMap.of("read_capacity_units", ImmutableList.of(mockSample, mockSample)), samples);
        verifyAll();
    }

    @Test
    void buildSamplesFromOT() {
        DoubleSummary doubleSummary = DoubleSummary.newBuilder()
                .build();
        Metric metric = Metric.newBuilder()
                .setName("amazonaws.com/AWS/DynamoDB/ReadCapacityUnits")
                .setDoubleSummary(doubleSummary)
                .build();

        Resource resource = Resource.newBuilder()
                .build();
        ResourceMetrics resourceMetrics = ResourceMetrics.newBuilder()
                .setResource(resource)
                .addInstrumentationLibraryMetrics(InstrumentationLibraryMetrics.newBuilder()
                        .addMetrics(metric)
                        .build())
                .build();

        ExportMetricsServiceRequest request = ExportMetricsServiceRequest.newBuilder()
                .addResourceMetrics(resourceMetrics)
                .build();

        SortedMap<String, String> labels = new TreeMap<>();
        Collector.MetricFamilySamples.Sample mockSample = new Collector.MetricFamilySamples.Sample(
                "read_capacity_units", ImmutableList.of("label"), ImmutableList.of("value"), 10.0D, 20_000L
        );

        Collector.MetricFamilySamples metricFamilySamples = new Collector.MetricFamilySamples(
                "read_capacity_units", GAUGE, "", ImmutableList.of(mockSample)
        );

        expect(sampleBuilder.buildFamily(ImmutableList.of(mockSample))).andReturn(metricFamilySamples);

        replayAll();
        testClass = new OpenTelemetryMetricConverter(metricNameUtil, sampleBuilder, scrapeConfigProvider) {
            @Override
            Map<String, String> extractAttributes(Resource actual) {
                assertEquals(resource, actual);
                return labels;
            }

            @Override
            void buildFromOTMetric(Map<String, List<Collector.MetricFamilySamples.Sample>> samplesByMetric,
                                   SortedMap<String, String> actualLabels, Metric actualMetric) {
                assertEquals(metric, actualMetric);
                assertEquals(labels, actualLabels);
                samplesByMetric.computeIfAbsent("read_capacity_units", k -> new ArrayList<>()).add(mockSample);
            }
        };

        assertEquals(ImmutableList.of(metricFamilySamples), testClass.buildSamplesFromOT(request));
        verifyAll();
    }
}
