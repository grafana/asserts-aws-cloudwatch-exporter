/*
 * Copyright © 2021
 * Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.cloudwatch.prometheus;

import ai.asserts.aws.MetricNameUtil;
import ai.asserts.aws.cloudwatch.query.MetricQuery;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableMap;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.cloudwatch.model.Metric;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
@Slf4j
@AllArgsConstructor
public class GaugeExporter {
    private final MetricNameUtil metricNameUtil;
    private final MetricCollectors metricCollectors;

    public void exportMetricMeta(String region, MetricQuery query) {
        Integer scrapeInterval = query.getMetricConfig().getNamespace().getScrapeInterval();
        Integer period = query.getMetricConfig().getNamespace().getPeriod();
        long nowMillis = now();
        metricCollectors.getGauge("cw_scrape_period_seconds", "")
                .addSample(ImmutableMap.of(
                        "region", region,
                        "namespace", query.getMetricConfig().getNamespace().getName()
                ), nowMillis, 1.0D * period);
        metricCollectors.getGauge("cw_scrape_interval_seconds", "")
                .addSample(ImmutableMap.of(
                        "region", region,
                        "namespace", query.getMetricConfig().getNamespace().getName()
                ), nowMillis, 1.0D * scrapeInterval);
    }

    public void exportMetric(String metricName,
                             String help,
                             Map<String, String> labels,
                             Instant timestamp, Double metric) {
        metricCollectors.getGauge(metricName, help)
                .addSample(labels, timestamp.toEpochMilli(), metric);
    }

    public void exportZeros(String region, Instant startTime, Instant endTime, Integer interval,
                            Map<String, MetricQuery> queriesById) {
        // Metrics without any data
        queriesById.values().forEach(metricQuery -> {
            Metric metric = metricQuery.getMetric();
            String exportedMetricName = metricNameUtil.exportedMetricName(metric,
                    metricQuery.getMetricStat());

            GaugeCollector gaugeCollector = metricCollectors.getGauge(exportedMetricName, "");

            // Zero fill if necessary
            int numDataPoints = (int) ((endTime.toEpochMilli() - startTime.toEpochMilli()) / (interval * 1000));
            List<Instant> timestamps = new ArrayList<>();
            List<Double> values = new ArrayList<>();
            for (int i = 0; i < numDataPoints; i++) {
                timestamps.add(startTime.plus((long) i * interval, ChronoUnit.SECONDS));
                values.add(0.0D);
            }
            gaugeCollector.addSample(region, metricQuery, interval, timestamps, values);
            log.debug("Zero filled metric for region = {}, metric = {}", region, exportedMetricName);
        });
    }

    @VisibleForTesting
    long now() {
        return Instant.now().toEpochMilli();
    }
}
