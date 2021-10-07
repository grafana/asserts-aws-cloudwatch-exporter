/*
 * Copyright © 2021
 * Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.cloudwatch.prometheus;

import ai.asserts.aws.MetricNameUtil;
import ai.asserts.aws.cloudwatch.query.MetricQuery;
import com.google.common.annotations.VisibleForTesting;
import io.prometheus.client.Collector;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import org.springframework.util.CollectionUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

import static io.prometheus.client.Collector.Type.GAUGE;
import static java.lang.String.format;

@EqualsAndHashCode(callSuper = false)
public class GaugeCollector extends Collector {
    private MetricNameUtil metricNameUtil = new MetricNameUtil();
    private final String metricName;
    private final String helpText;

    @EqualsAndHashCode.Exclude
    private volatile Map<Map<String, String>, MetricSamples> samplesByMetric = new ConcurrentHashMap<>();

    public GaugeCollector(String metricName, String helpText) {
        this.metricName = metricName;
        this.helpText = helpText;
    }

    @VisibleForTesting
    public GaugeCollector(String metricName, String helpText, MetricNameUtil metricNameUtil) {
        this.metricName = metricName;
        this.helpText = helpText;
        this.metricNameUtil = metricNameUtil;
    }

    @Override
    public List<MetricFamilySamples> collect() {
        Map<Map<String, String>, MetricSamples> collectFrom = samplesByMetric;
        samplesByMetric = new ConcurrentHashMap<>();

        List<MetricFamilySamples.Sample> samples = new ArrayList<>();
        collectFrom.forEach((labels, metricSamples) ->
                metricSamples.getMetrics().forEach((ts, value) ->
                        samples.add(newSample(labels, ts, value))));

        List<MetricFamilySamples> metricFamilySamples = new ArrayList<>();
        metricFamilySamples.add(new MetricFamilySamples(metricName, GAUGE, helpText, samples));
        return metricFamilySamples;
    }

    public void addSample(String region, MetricQuery metricQuery, int period,
                          List<Instant> timestamps, List<Double> values) {
        // Build labels
        Map<String, String> labels = new TreeMap<>();
        labels.put("region", region);
        metricQuery.getMetric().dimensions().forEach(dimension -> {
            String key = format("d_%s", metricNameUtil.toSnakeCase(dimension.name()));
            labels.put(key, dimension.value());
        });

        if (metricQuery.getResource() != null && !CollectionUtils.isEmpty(metricQuery.getResource().getTags())) {
            metricQuery.getResource().getTags().forEach(tag ->
                    labels.put(format("tag_%s", tag.key()), tag.value()));
        }

        for (int i = 0; i < timestamps.size(); i++) {
            addSample(labels, timestamps.get(i).plusSeconds(period).toEpochMilli(), values.get(i));
        }
    }

    public void addSample(Map<String, String> labels, Long timestamp, Double value) {
        samplesByMetric.computeIfAbsent(labels, k -> new MetricSamples()).put(timestamp, value);
    }

    private MetricFamilySamples.Sample newSample(Map<String, String> labels, Long ts, Double value) {
        return new MetricFamilySamples.Sample(metricName,
                new ArrayList<>(labels.keySet()),
                new ArrayList<>(labels.values()),
                value, ts
        );
    }

    @Getter
    public static class MetricSamples {
        private final Map<Long, Double> metrics = new TreeMap<>();

        public void put(Long ts, Double value) {
            metrics.put(ts, value);
        }
    }
}
