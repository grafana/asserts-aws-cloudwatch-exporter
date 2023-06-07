/*
 *  Copyright © 2022.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.cloudwatch.alarms;

import ai.asserts.aws.MetricNameUtil;
import ai.asserts.aws.exporter.BasicMetricCollector;
import ai.asserts.aws.exporter.MetricSampleBuilder;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableSet;
import io.prometheus.client.Collector;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
public class AlarmMetricExporter extends Collector {
    private final MetricSampleBuilder sampleBuilder;
    private final BasicMetricCollector basicMetricCollector;
    private final AlarmMetricConverter alarmMetricConverter;
    @Getter
    private final Map<String, Map<String, String>> alarmLabels = new ConcurrentHashMap<>();

    public AlarmMetricExporter(MetricSampleBuilder sampleBuilder,
                               BasicMetricCollector basicMetricCollector,
                               AlarmMetricConverter alarmMetricConverter) {
        this.sampleBuilder = sampleBuilder;
        this.basicMetricCollector = basicMetricCollector;
        this.alarmMetricConverter = alarmMetricConverter;
    }

    public void processMetric(List<Map<String, String>> labelsList) {
        labelsList.forEach(labels -> {
            if ("ALARM".equals(labels.get("state"))) {
                addMetric(labels);
            } else {
                removeMetric(labels);
            }
        });
    }

    private void addMetric(Map<String, String> labels) {
        Optional<String> key = getKey(labels);
        key.ifPresent(keyValue -> {
            log.info("Adding alert - {}", keyValue);
            labels.remove("state");
            alarmLabels.put(keyValue, labels);
        });
    }

    private void removeMetric(Map<String, String> labels) {
        Optional<String> key = getKey(labels);
        key.ifPresent(keyValue -> {
            log.info("Stopping alert - {}", keyValue);
            alarmLabels.remove(keyValue);
        });
    }

    private Optional<String> getKey(Map<String, String> labels) {
        Set<String> keyLabels = ImmutableSet.of("account_id", "region", "namespace", "metric_name", "alarm_name",
                "original_alarm_name");
        return Optional.of(keyLabels.stream()
                .filter(labels::containsKey)
                .map(labels::get)
                .collect(Collectors.joining("_")));
    }

    @Override
    public List<MetricFamilySamples> collect() {
        List<MetricFamilySamples> latest = new ArrayList<>();
        try {
            if (alarmLabels.size() > 0) {
                List<MetricFamilySamples.Sample> metrics = new ArrayList<>();
                alarmLabels.values().forEach(labels -> {
                    alarmMetricConverter.simplifyAlarmName(labels);
                    if (labels.containsKey("timestamp")) {
                        Instant timestamp = Instant.parse(labels.get("timestamp"));
                        recordHistogram(labels, timestamp);
                        labels.remove("timestamp");
                    }
                    sampleBuilder.buildSingleSample("aws_cloudwatch_alarm", labels, 1.0)
                            .ifPresent(metrics::add);
                });
                sampleBuilder.buildFamily(metrics).ifPresent(latest::add);
            }
            log.info("Built {} alarm metrics", latest.size());
        } catch (Exception e) {
            log.error("Failed to build cloudwatch alarm metrics", e);
        }
        return latest;
    }

    private void recordHistogram(Map<String, String> labels, Instant timestamp) {
        SortedMap<String, String> histoLabels = new TreeMap<>();
        histoLabels.put("namespace", labels.get("namespace"));
        histoLabels.put("account_id", labels.get("account_id"));
        histoLabels.put("region", labels.get("region"));
        histoLabels.put("alarm_name", labels.get("alarm_name"));
        if (labels.containsKey("original_alarm_name")) {
            histoLabels.put("original_alarm_name", labels.get("original_alarm_name"));
        }
        long diff = (now().toEpochMilli() - timestamp.toEpochMilli()) / 1000;
        this.basicMetricCollector.recordHistogram(MetricNameUtil.EXPORTER_DELAY_SECONDS, histoLabels, diff);
    }

    @VisibleForTesting
    Instant now() {
        return Instant.now();
    }
}
