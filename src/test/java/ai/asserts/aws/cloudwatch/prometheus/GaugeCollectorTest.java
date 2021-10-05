/*
 * Copyright © 2021
 * Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.cloudwatch.prometheus;

import ai.asserts.aws.MetricNameUtil;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.prometheus.client.Collector;
import org.easymock.EasyMockSupport;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.cloudwatch.model.Dimension;
import software.amazon.awssdk.services.cloudwatch.model.Metric;

import java.time.Instant;
import java.util.List;

import static org.easymock.EasyMock.expect;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class GaugeCollectorTest extends EasyMockSupport {
    @Test
    public void addSample() {
        GaugeCollector gaugeCollector = new GaugeCollector("metric", "help");
        Instant now = Instant.now();
        gaugeCollector.addSample(ImmutableMap.of("label", "value1"), now.toEpochMilli(), 1.0D);
        gaugeCollector.addSample(ImmutableMap.of("label", "value2"), now.toEpochMilli(), 2.0D);

        List<Collector.MetricFamilySamples> metricFamilySamples = gaugeCollector.collect();
        assertEquals(1, metricFamilySamples.size());

        Collector.MetricFamilySamples familSamples = metricFamilySamples.get(0);
        assertEquals(2, familSamples.samples.size());
        assertEquals(
                new Collector.MetricFamilySamples.Sample("metric",
                        ImmutableList.of("label"), ImmutableList.of("value1"), 1.0D, now.toEpochMilli()),
                familSamples.samples.get(0));
        assertEquals(
                new Collector.MetricFamilySamples.Sample("metric",
                        ImmutableList.of("label"), ImmutableList.of("value2"), 2.0D, now.toEpochMilli()),
                familSamples.samples.get(1));
    }

    @Test
    public void addSamples() {
        MetricNameUtil metricNameUtil = mock(MetricNameUtil.class);
        GaugeCollector gaugeCollector = new GaugeCollector("metric", "help",
                metricNameUtil);
        Instant end = Instant.now();
        Instant start = end.minusSeconds(120);

        expect(metricNameUtil.toSnakeCase("dim1")).andReturn("dim1");
        expect(metricNameUtil.toSnakeCase("dim2")).andReturn("dim2");
        replayAll();
        gaugeCollector.addSample("region1",
                Metric.builder()
                        .dimensions(
                                Dimension.builder().name("dim1").value("value1").build(),
                                Dimension.builder().name("dim2").value("value2").build())
                        .build()
                , 60,
                ImmutableList.of(start, start.plusSeconds(60)), ImmutableList.of(1.0D, 2.0D)
        );

        List<Collector.MetricFamilySamples> metricFamilySamples = gaugeCollector.collect();
        assertEquals(1, metricFamilySamples.size());

        Collector.MetricFamilySamples familSamples = metricFamilySamples.get(0);
        assertEquals(2, familSamples.samples.size());
        assertEquals(
                new Collector.MetricFamilySamples.Sample("metric",
                        ImmutableList.of("d_dim1", "d_dim2", "region"), ImmutableList.of("value1", "value2", "region1"),
                        1.0D, start.plusSeconds(60).toEpochMilli()),
                familSamples.samples.get(0));
        assertEquals(
                new Collector.MetricFamilySamples.Sample("metric",
                        ImmutableList.of("d_dim1", "d_dim2", "region"), ImmutableList.of("value1", "value2", "region1"),
                        2.0D, start.plusSeconds(120).toEpochMilli()),
                familSamples.samples.get(1));
        verifyAll();
    }
}
