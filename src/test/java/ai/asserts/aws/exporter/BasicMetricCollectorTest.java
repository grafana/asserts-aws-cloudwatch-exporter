/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.exporter;

import ai.asserts.aws.config.ScrapeConfig;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedMap;
import io.prometheus.client.Collector;
import io.prometheus.client.Collector.MetricFamilySamples.Sample;
import org.easymock.EasyMockSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;

import static io.prometheus.client.Collector.Type.COUNTER;
import static io.prometheus.client.Collector.Type.GAUGE;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class BasicMetricCollectorTest extends EasyMockSupport {
    private ScrapeConfig scrapeConfig;
    private BasicMetricCollector metricCollector;

    @BeforeEach
    public void setup() {
        metricCollector = new BasicMetricCollector();
    }

    @Test
    void collect_gauge() {
        SortedMap<String, String> labels1 = new TreeMap<>(
                ImmutableSortedMap.of("label1", "value1", "label2", "value2"));
        SortedMap<String, String> labels2 = new TreeMap<>(
                ImmutableSortedMap.of("label1", "value11", "label2", "value22"));

        replayAll();
        metricCollector.recordGaugeValue("metric", labels1, 1.0D);

        Sample sample1 = new Sample(
                "metric",
                ImmutableList.of("label1", "label2"),
                ImmutableList.of("value1", "value2"), 1.0D
        );
        List<Collector.MetricFamilySamples> collect1 = metricCollector.collect();
        assertAll(
                () -> assertEquals(1, collect1.size()),
                () -> assertEquals("metric", collect1.get(0).name),
                () -> assertEquals(GAUGE, collect1.get(0).type),
                () -> assertEquals(1, collect1.get(0).samples.size()),
                () -> assertEquals(sample1, collect1.get(0).samples.get(0))
        );

        metricCollector.recordGaugeValue("metric", labels1, 2.0D);
        List<Collector.MetricFamilySamples> collect2 = metricCollector.collect();

        Sample sample2 = new Sample(
                "metric",
                ImmutableList.of("label1", "label2"),
                ImmutableList.of("value1", "value2"), 2.0D
        );
        assertAll(
                () -> assertEquals(1, collect2.size()),
                () -> assertEquals("metric", collect2.get(0).name),
                () -> assertEquals(GAUGE, collect2.get(0).type),
                () -> assertEquals(1, collect2.get(0).samples.size()),
                () -> assertEquals(sample2, collect2.get(0).samples.get(0))
        );

        Sample sample3 = new Sample(
                "metric",
                ImmutableList.of("label1", "label2"),
                ImmutableList.of("value11", "value22"), 3.0D
        );
        metricCollector.recordGaugeValue("metric", labels2, 3.0D);
        List<Collector.MetricFamilySamples> collect3 = metricCollector.collect();

        assertAll(
                () -> assertEquals(1, collect3.size()),
                () -> assertEquals("metric", collect3.get(0).name),
                () -> assertEquals(GAUGE, collect3.get(0).type),
                () -> assertEquals(1, collect3.get(0).samples.size()),
                () -> assertTrue(collect3.get(0).samples.contains(sample3))
        );
    }

    @Test
    void collect_counter() {
        SortedMap<String, String> labels1 = new TreeMap<>(
                ImmutableSortedMap.of("label1", "value1", "label2", "value2"));
        SortedMap<String, String> labels2 = new TreeMap<>(
                ImmutableSortedMap.of("label1", "value11", "label2", "value22"));

        replayAll();

        metricCollector.recordCounterValue("metric", labels1, 1);

        Sample sample1 = new Sample(
                "metric",
                ImmutableList.of("label1", "label2"),
                ImmutableList.of("value1", "value2"), 1.0D
        );
        List<Collector.MetricFamilySamples> collect1 = metricCollector.collect();
        assertAll(
                () -> assertEquals(1, collect1.size()),
                () -> assertEquals("metric", collect1.get(0).name),
                () -> assertEquals(COUNTER, collect1.get(0).type),
                () -> assertEquals(1, collect1.get(0).samples.size()),
                () -> assertEquals(sample1, collect1.get(0).samples.get(0))
        );

        metricCollector.recordCounterValue("metric", labels1, 2);
        List<Collector.MetricFamilySamples> collect2 = metricCollector.collect();

        Sample sample2 = new Sample(
                "metric",
                ImmutableList.of("label1", "label2"),
                ImmutableList.of("value1", "value2"), 3.0D
        );
        assertAll(
                () -> assertEquals(1, collect2.size()),
                () -> assertEquals("metric", collect2.get(0).name),
                () -> assertEquals(COUNTER, collect2.get(0).type),
                () -> assertEquals(1, collect2.get(0).samples.size()),
                () -> assertEquals(sample2, collect2.get(0).samples.get(0))
        );

        Sample sample3 = new Sample(
                "metric",
                ImmutableList.of("label1", "label2"),
                ImmutableList.of("value11", "value22"), 5.0D
        );
        metricCollector.recordCounterValue("metric", labels2, 5);
        List<Collector.MetricFamilySamples> collect3 = metricCollector.collect();

        assertAll(
                () -> assertEquals(1, collect3.size()),
                () -> assertEquals("metric", collect3.get(0).name),
                () -> assertEquals(COUNTER, collect3.get(0).type),
                () -> assertEquals(2, collect3.get(0).samples.size()),
                () -> assertTrue(collect3.get(0).samples.contains(sample2)),
                () -> assertTrue(collect3.get(0).samples.contains(sample3))
        );
    }

    @Test
    void collect_latency() {
        SortedMap<String, String> labels1 = new TreeMap<>(
                ImmutableSortedMap.of("label1", "value1", "label2", "value2"));
        SortedMap<String, String> labels2 = new TreeMap<>(
                ImmutableSortedMap.of("label1", "value11", "label2", "value22"));

        replayAll();

        metricCollector.recordLatency("metric", labels1, 10);

        Sample sample1_count = new Sample(
                "metric_count",
                ImmutableList.of("label1", "label2"),
                ImmutableList.of("value1", "value2"), 1.0D
        );
        Sample sample1_sum = new Sample(
                "metric_sum",
                ImmutableList.of("label1", "label2"),
                ImmutableList.of("value1", "value2"), 10.0D
        );
        List<Collector.MetricFamilySamples> collect1 = metricCollector.collect();
        assertAll(
                () -> assertEquals(2, collect1.size()),
                () -> assertEquals("metric_count", collect1.get(0).name),
                () -> assertEquals(COUNTER, collect1.get(0).type),
                () -> assertEquals("metric_sum", collect1.get(1).name),
                () -> assertEquals(COUNTER, collect1.get(1).type),
                () -> assertEquals(1, collect1.get(0).samples.size()),
                () -> assertEquals(1, collect1.get(1).samples.size()),
                () -> assertTrue(collect1.get(0).samples.contains(sample1_count)),
                () -> assertTrue(collect1.get(1).samples.contains(sample1_sum))
        );

        metricCollector.recordLatency("metric", labels1, 20);
        List<Collector.MetricFamilySamples> collect2 = metricCollector.collect();

        Sample sample2_count = new Sample(
                "metric_count",
                ImmutableList.of("label1", "label2"),
                ImmutableList.of("value1", "value2"), 2.0D
        );
        Sample sample2_sum = new Sample(
                "metric_sum",
                ImmutableList.of("label1", "label2"),
                ImmutableList.of("value1", "value2"), 30.0D
        );
        assertAll(
                () -> assertEquals(2, collect2.size()),
                () -> assertEquals("metric_count", collect2.get(0).name),
                () -> assertEquals(COUNTER, collect2.get(0).type),
                () -> assertEquals("metric_sum", collect2.get(1).name),
                () -> assertEquals(COUNTER, collect2.get(1).type),
                () -> assertEquals(1, collect2.get(0).samples.size()),
                () -> assertEquals(1, collect2.get(1).samples.size()),
                () -> assertTrue(collect2.get(0).samples.contains(sample2_count)),
                () -> assertTrue(collect2.get(1).samples.contains(sample2_sum))
        );

        metricCollector.recordLatency("metric", labels2, 20);
        List<Collector.MetricFamilySamples> collect3 = metricCollector.collect();
        Sample sample3_count = new Sample(
                "metric_count",
                ImmutableList.of("label1", "label2"),
                ImmutableList.of("value11", "value22"), 1.0D
        );
        Sample sample3_sum = new Sample(
                "metric_sum",
                ImmutableList.of("label1", "label2"),
                ImmutableList.of("value11", "value22"), 20.0D
        );

        assertAll(
                () -> assertEquals(2, collect3.size()),
                () -> assertEquals("metric_count", collect3.get(0).name),
                () -> assertEquals(COUNTER, collect3.get(0).type),
                () -> assertEquals("metric_sum", collect3.get(1).name),
                () -> assertEquals(COUNTER, collect3.get(1).type),
                () -> assertEquals(2, collect3.get(0).samples.size()),
                () -> assertEquals(2, collect3.get(1).samples.size()),
                () -> assertTrue(collect3.get(0).samples.contains(sample2_count)),
                () -> assertTrue(collect3.get(1).samples.contains(sample2_sum)),
                () -> assertTrue(collect3.get(0).samples.contains(sample3_count)),
                () -> assertTrue(collect3.get(1).samples.contains(sample3_sum))
        );
    }
}
