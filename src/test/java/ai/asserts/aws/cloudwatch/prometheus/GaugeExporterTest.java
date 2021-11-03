/*
 * Copyright © 2021
 * Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.cloudwatch.prometheus;

import ai.asserts.aws.MetricNameUtil;
import ai.asserts.aws.cloudwatch.config.MetricConfig;
import ai.asserts.aws.cloudwatch.config.NamespaceConfig;
import ai.asserts.aws.cloudwatch.model.CWNamespace;
import ai.asserts.aws.cloudwatch.model.MetricStat;
import ai.asserts.aws.cloudwatch.query.MetricQuery;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.easymock.EasyMockSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.cloudwatch.model.Metric;

import java.time.Instant;

import static org.easymock.EasyMock.expect;

public class GaugeExporterTest extends EasyMockSupport {
    private MetricNameUtil metricNameUtil;
    private MetricCollectors metricCollectors;
    private GaugeCollector gaugeCollector;
    private long now;
    private Metric fooBarMetric;
    private MetricQuery metricQuery;
    private GaugeExporter testClass;

    @BeforeEach
    public void setup() {
        metricNameUtil = mock(MetricNameUtil.class);
        metricCollectors = mock(MetricCollectors.class);
        gaugeCollector = mock(GaugeCollector.class);
        now = Instant.now().toEpochMilli();
        fooBarMetric = Metric.builder()
                .metricName("FooBar")
                .build();
        metricQuery = MetricQuery.builder()
                .metric(fooBarMetric)
                .metricConfig(MetricConfig.builder()
                        .namespace(NamespaceConfig.builder()
                                .period(300)
                                .scrapeInterval(60)
                                .name(CWNamespace.lambda.name())
                                .build())
                        .scrapeInterval(60)
                        .period(300)
                        .build())
                .metricStat(MetricStat.Average)
                .build();
        testClass = new GaugeExporter(metricNameUtil, metricCollectors) {
            @Override
            long now() {
                return now;
            }
        };
    }

    @Test
    void exportMetricMeta() {
        expect(metricCollectors.getGauge("cw_scrape_period_seconds", "")).andReturn(gaugeCollector);
        expect(metricCollectors.getGauge("cw_scrape_interval_seconds", "")).andReturn(gaugeCollector);

        gaugeCollector.addSample(ImmutableMap.of("region", "region-1", "namespace", "lambda"),
                now, metricQuery.getMetricConfig().getPeriod() * 1.0D);
        gaugeCollector.addSample(ImmutableMap.of("region", "region-1", "namespace", "lambda"),
                now, metricQuery.getMetricConfig().getScrapeInterval() * 1.0D);

        replayAll();
        testClass.exportMetricMeta("region-1", metricQuery);
        verifyAll();
    }

    @Test
    void exportMetric() {
        Instant now = Instant.now();
        ImmutableMap<String, String> labels = ImmutableMap.of("label", "value");
        expect(metricCollectors.getGauge("foo_bar", "help")).andReturn(gaugeCollector);

        gaugeCollector.addSample(labels, now.toEpochMilli(), 20.0D);

        replayAll();
        testClass.exportMetric("foo_bar", "help", labels, now, 20.0D);
        verifyAll();
    }

    @Test
    void exportZeros() {
        Instant thisInstant = Instant.now();
        Instant endTime = thisInstant.minusSeconds(5);
        Instant startTime = endTime.minusSeconds(metricQuery.getMetricConfig().getScrapeInterval() * 2);

        Integer scrapeInterval = metricQuery.getMetricConfig().getScrapeInterval();

        expect(metricNameUtil.exportedMetricName(metricQuery.getMetric(), metricQuery.getMetricStat()))
                .andReturn("foo_bar");
        expect(metricCollectors.getGauge("foo_bar", "")).andReturn(gaugeCollector);

        gaugeCollector.addSample("region1", metricQuery, scrapeInterval,
                ImmutableList.of(startTime, startTime.plusSeconds(scrapeInterval)),
                ImmutableList.of(0.0D, 0.0D)
        );

        replayAll();
        testClass.exportZeros("region1", startTime, endTime, scrapeInterval, ImmutableMap.of("1", metricQuery));
        verifyAll();
    }
}
