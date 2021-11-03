/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.cloudwatch.metrics;

import ai.asserts.aws.MetricNameUtil;
import ai.asserts.aws.cloudwatch.model.MetricStat;
import ai.asserts.aws.cloudwatch.prometheus.LabelBuilder;
import ai.asserts.aws.cloudwatch.query.MetricQuery;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedMap;
import io.prometheus.client.Collector;
import io.prometheus.client.Collector.MetricFamilySamples.Sample;
import org.easymock.EasyMockSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.cloudwatch.model.Metric;
import software.amazon.awssdk.services.cloudwatch.model.MetricDataResult;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static io.prometheus.client.Collector.Type.GAUGE;
import static org.easymock.EasyMock.expect;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class MetricSampleBuilderTest extends EasyMockSupport {
    private MetricNameUtil metricNameUtil;
    private LabelBuilder labelBuilder;
    private MetricSampleBuilder testClass;

    @BeforeEach
    public void setup() {
        metricNameUtil = mock(MetricNameUtil.class);
        labelBuilder = mock(LabelBuilder.class);
        testClass = new MetricSampleBuilder(metricNameUtil, labelBuilder);
    }

    @Test
    void buildSamples() {
        Metric metric = Metric.builder().build();
        Instant instant = Instant.now();

        MetricQuery metricQuery = MetricQuery.builder()
                .metric(metric)
                .metricStat(MetricStat.Average)
                .build();
        expect(metricNameUtil.exportedMetricName(metric, MetricStat.Average)).andReturn("metric");
        expect(labelBuilder.buildLabels("region", metricQuery)).andReturn(
                ImmutableSortedMap.of("label1", "value1", "label2", "value2"));
        replayAll();

        List<Sample> samples = testClass.buildSamples("region",
                metricQuery,
                MetricDataResult.builder()
                        .timestamps(instant, instant.plusSeconds(60))
                        .values(1.0D, 2.0D)
                        .build(), 60);
        List<String> labelNames = Arrays.asList("label1", "label2");
        List<String> labelValues = Arrays.asList("value1", "value2");
        assertEquals(ImmutableList.of(
                new Sample("metric", labelNames, labelValues, 1.0D, instant.plusSeconds(60).toEpochMilli()),
                new Sample("metric", labelNames, labelValues, 2.0D, instant.plusSeconds(120).toEpochMilli())
        ), samples);
        verifyAll();
    }

    @Test
    void buildSingleSample() {
        Instant instant = Instant.now();
        replayAll();
        List<String> labelNames = Arrays.asList("label1", "label2");
        List<String> labelValues = Arrays.asList("value1", "value2");
        assertEquals(new Sample("metric", labelNames, labelValues, 1.0D, instant.toEpochMilli()),
                testClass.buildSingleSample("metric",
                        ImmutableSortedMap.of("label1", "value1", "label2", "value2"),
                        instant, 1.0D
                ));

        verifyAll();
    }

    @Test
    void buildFamily() {
        Instant instant = Instant.now();
        replayAll();
        Sample sample = new Sample("metric",
                Collections.emptyList(),
                Collections.emptyList(), 1.0D, instant.toEpochMilli());
        assertEquals(
                new Collector.MetricFamilySamples("metric", GAUGE, "", ImmutableList.of(sample, sample)),
                testClass.buildFamily(ImmutableList.of(sample, sample))
        );

        verifyAll();
    }
}