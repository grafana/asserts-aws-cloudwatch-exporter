/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.exporter;

import ai.asserts.aws.MetricNameUtil;
import ai.asserts.aws.TaskExecutorUtil;
import ai.asserts.aws.account.AWSAccount;
import ai.asserts.aws.cloudwatch.query.MetricQuery;
import ai.asserts.aws.model.MetricStat;
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
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;

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
        TaskExecutorUtil taskExecutorUtil = mock(TaskExecutorUtil.class);
        testClass = new MetricSampleBuilder(metricNameUtil, labelBuilder, taskExecutorUtil);
        expect(taskExecutorUtil.getAccountDetails()).andReturn(AWSAccount.builder()
                        .name("dev")
                        .tenant("acme")
                        .accountId("account")
                .build()).anyTimes();
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
        SortedMap<String, String> labels = new TreeMap<>(ImmutableSortedMap.of(
                "label1", "value1", "label2", "value2"
        ));
        expect(labelBuilder.buildLabels("account", "region", metricQuery)).andReturn(labels);
        replayAll();

        List<Sample> samples = testClass.buildSamples("account", "region",
                metricQuery,
                MetricDataResult.builder()
                        .timestamps(instant, instant.plusSeconds(60))
                        .values(1.0D, 2.0D)
                        .build());
        List<String> labelNames = Arrays.asList("asserts_env", "asserts_site", "label1", "label2", "tenant");
        List<String> labelValues = Arrays.asList("dev", "region", "value1", "value2", "acme");
        assertEquals(ImmutableList.of(
                new Sample("metric", labelNames, labelValues, 1.0D),
                new Sample("metric", labelNames, labelValues, 2.0D)
        ), samples);
        verifyAll();
    }

    @Test
    void buildSingleSample() {
        List<String> labelNames = Arrays.asList("asserts_env", "label1", "label2", "tenant");
        List<String> labelValues = Arrays.asList("dev", "value1", "value2", "acme");
        SortedMap<String, String> labels = new TreeMap<>(
                ImmutableSortedMap.of("label1", "value1", "label2", "value2"));
        replayAll();
        assertEquals(Optional.of(new Sample("metric", labelNames, labelValues, 1.0D)),
                testClass.buildSingleSample("metric",
                        labels,
                        1.0D
                ));

        verifyAll();
    }

    @Test
    void buildFamily() {
        replayAll();
        Sample sample = new Sample("metric",
                Collections.emptyList(),
                Collections.emptyList(), 1.0D);
        assertEquals(
                Optional.of(new Collector.MetricFamilySamples("metric", GAUGE, "", ImmutableList.of(sample, sample))),
                testClass.buildFamily(ImmutableList.of(sample, sample))
        );

        verifyAll();
    }

    @Test
    void buildFamily_NoSamples() {
        replayAll();
        assertEquals(
                Optional.empty(),
                testClass.buildFamily(ImmutableList.of())
        );

        verifyAll();
    }
}
