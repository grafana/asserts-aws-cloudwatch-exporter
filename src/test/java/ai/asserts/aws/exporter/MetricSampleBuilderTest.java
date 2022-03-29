/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.exporter;

import ai.asserts.aws.MetricNameUtil;
import ai.asserts.aws.cloudwatch.config.ScrapeConfig;
import ai.asserts.aws.cloudwatch.config.ScrapeConfigProvider;
import ai.asserts.aws.cloudwatch.model.MetricStat;
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
    private ScrapeConfigProvider scrapeConfigProvider;
    private ScrapeConfig scrapeConfig;
    private MetricSampleBuilder testClass;

    @BeforeEach
    public void setup() {
        metricNameUtil = mock(MetricNameUtil.class);
        labelBuilder = mock(LabelBuilder.class);
        scrapeConfigProvider = mock(ScrapeConfigProvider.class);
        scrapeConfig = mock(ScrapeConfig.class);
        testClass = new MetricSampleBuilder(metricNameUtil, labelBuilder, scrapeConfigProvider);
        expect(scrapeConfigProvider.getScrapeConfig()).andReturn(scrapeConfig).anyTimes();
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
        ImmutableSortedMap<String, String> labels = ImmutableSortedMap.of("label1", "value1", "label2", "value2");
        expect(labelBuilder.buildLabels("region", metricQuery)).andReturn(labels);
        expect(scrapeConfig.additionalLabels("metric", labels)).andReturn(labels);
        replayAll();

        List<Sample> samples = testClass.buildSamples("region",
                metricQuery,
                MetricDataResult.builder()
                        .timestamps(instant, instant.plusSeconds(60))
                        .values(1.0D, 2.0D)
                        .build());
        List<String> labelNames = Arrays.asList("label1", "label2");
        List<String> labelValues = Arrays.asList("value1", "value2");
        assertEquals(ImmutableList.of(
                new Sample("metric", labelNames, labelValues, 1.0D),
                new Sample("metric", labelNames, labelValues, 2.0D)
        ), samples);
        verifyAll();
    }

    @Test
    void buildSingleSample() {
        List<String> labelNames = Arrays.asList("label1", "label2");
        List<String> labelValues = Arrays.asList("value1", "value2");
        ImmutableSortedMap<String, String> labels = ImmutableSortedMap.of("label1", "value1", "label2", "value2");
        expect(scrapeConfig.additionalLabels("metric", labels)).andReturn(labels);
        replayAll();
        assertEquals(new Sample("metric", labelNames, labelValues, 1.0D),
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
                new Collector.MetricFamilySamples("metric", GAUGE, "", ImmutableList.of(sample, sample)),
                testClass.buildFamily(ImmutableList.of(sample, sample))
        );

        verifyAll();
    }
}
