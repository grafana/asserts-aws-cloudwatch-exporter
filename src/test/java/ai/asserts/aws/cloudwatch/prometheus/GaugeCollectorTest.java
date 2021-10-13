/*
 * Copyright © 2021
 * Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.cloudwatch.prometheus;

import ai.asserts.aws.MetricNameUtil;
import ai.asserts.aws.cloudwatch.query.MetricQuery;
import ai.asserts.aws.resource.Resource;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;
import io.prometheus.client.Collector;
import org.easymock.EasyMockSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.cloudwatch.model.Dimension;
import software.amazon.awssdk.services.cloudwatch.model.Metric;
import software.amazon.awssdk.services.resourcegroupstaggingapi.model.Tag;

import java.time.Instant;
import java.util.List;

import static org.easymock.EasyMock.expect;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class GaugeCollectorTest extends EasyMockSupport {
    private MetricNameUtil metricNameUtil;
    private LabelBuilder labelBuilder;

    @BeforeEach
    public void setup() {
        metricNameUtil = mock(MetricNameUtil.class);
        labelBuilder = mock(LabelBuilder.class);
    }

    @Test
    public void addSample() {
        GaugeCollector gaugeCollector = new GaugeCollector(metricNameUtil, labelBuilder, "metric", "help");
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
        MetricQuery metricQuery = MetricQuery.builder()
                .metric(
                        Metric.builder()
                                .dimensions(
                                        Dimension.builder().name("dim1").value("value1").build(),
                                        Dimension.builder().name("dim2").value("value2").build())
                                .build())
                .resource(Resource.builder()
                        .tags(ImmutableList.of(Tag.builder()
                                .key("tag1")
                                .value("value")
                                .build()))
                        .build())
                .build();

        GaugeCollector gaugeCollector = new GaugeCollector(metricNameUtil, labelBuilder, "metric", "help");
        Instant end = Instant.now();
        Instant start = end.minusSeconds(120);

        expect(labelBuilder.buildLabels("region1", metricQuery)).andReturn(
                ImmutableSortedMap.of(
                        "label1", "value1", "label2", "value2"
                )
        );

        replayAll();

        gaugeCollector.addSample("region1",
                metricQuery
                , 60,
                ImmutableList.of(start, start.plusSeconds(60)), ImmutableList.of(1.0D, 2.0D)
        );

        List<Collector.MetricFamilySamples> metricFamilySamples = gaugeCollector.collect();
        assertEquals(1, metricFamilySamples.size());

        Collector.MetricFamilySamples familSamples = metricFamilySamples.get(0);
        assertEquals(2, familSamples.samples.size());
        assertTrue(
                familSamples.samples.contains(new Collector.MetricFamilySamples.Sample("metric",
                        ImmutableList.of("label1", "label2"),
                        ImmutableList.of("value1", "value2"),
                        1.0D, start.plusSeconds(60).toEpochMilli())));
        assertTrue(familSamples.samples.contains(new Collector.MetricFamilySamples.Sample("metric",
                ImmutableList.of("label1", "label2"),
                ImmutableList.of("value1", "value2"),
                2.0D, start.plusSeconds(120).toEpochMilli())));
        verifyAll();
    }
}
