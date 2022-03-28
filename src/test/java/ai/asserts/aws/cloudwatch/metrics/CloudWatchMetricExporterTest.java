/*
 *  Copyright © 2022.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.cloudwatch.metrics;

import ai.asserts.aws.exporter.MetricSampleBuilder;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.prometheus.client.Collector;
import org.easymock.EasyMockSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.easymock.EasyMock.expect;

public class CloudWatchMetricExporterTest extends EasyMockSupport {
    private MetricSampleBuilder sampleBuilder;
    private Collector.MetricFamilySamples.Sample sample;
    private Collector.MetricFamilySamples samples;
    private CloudWatchMetricExporter testClass;

    @BeforeEach
    public void setup() {
        sampleBuilder = mock(MetricSampleBuilder.class);
        sample = mock(Collector.MetricFamilySamples.Sample.class);
        samples = mock(Collector.MetricFamilySamples.class);
        testClass = new CloudWatchMetricExporter(sampleBuilder);
    }

    @Test
    public void collect() {
        expect(sampleBuilder.buildSingleSample("aws_cloudwatch_metrics",
                ImmutableMap.of("metric_name", "m1"), 1.0)).andReturn(sample);
        expect(sampleBuilder.buildFamily(ImmutableList.of(sample))).andReturn(samples);

        replayAll();
        testClass.addMetric(ImmutableMap.of("metric_name", "m1"));
        testClass.collect();
        verifyAll();
    }
}
