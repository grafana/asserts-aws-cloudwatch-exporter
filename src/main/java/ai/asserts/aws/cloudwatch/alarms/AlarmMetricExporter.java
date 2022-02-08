/*
 *  Copyright © 2022.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.cloudwatch.alarms;

import ai.asserts.aws.cloudwatch.TimeWindowBuilder;
import ai.asserts.aws.exporter.MetricProvider;
import ai.asserts.aws.exporter.MetricSampleBuilder;
import io.prometheus.client.Collector;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

@Slf4j
@Component
public class AlarmMetricExporter extends Collector implements MetricProvider {

    private final MetricSampleBuilder sampleBuilder;
    private final TimeWindowBuilder timeWindowBuilder;
    private volatile List<MetricFamilySamples> metrics = new ArrayList<>();
    @Getter
    private Map<String, Map<String, String>> alarmLabels = new TreeMap<>();

    public AlarmMetricExporter(MetricSampleBuilder sampleBuilder,
                               TimeWindowBuilder timeWindowBuilder) {
        this.sampleBuilder = sampleBuilder;
        this.timeWindowBuilder = timeWindowBuilder;
    }

    public void processMetric(List<Map<String, String>> labels) {
        labels.forEach(labellist -> {
            if ("ALARM".equals(labellist.get("state"))) {
                addMetric(labellist);
            } else {
                removeMetric(labellist);
            }
        });
    }

    private void addMetric(Map<String, String> labels) {
        Optional<String> key = getKey(labels);
        if (key.isPresent()) {
            log.info("Adding alert - {}", labels.get("alertname"));
            labels.remove("state");
            alarmLabels.put(key.get(), labels);
        }
    }

    private void removeMetric(Map<String, String> labels) {
        Optional<String> key = getKey(labels);
        if (key.isPresent()) {
            alarmLabels.remove(key.get());
            log.info("Stopping alert - {}", labels.get("alertname"));
        }
    }

    private Optional<String> getKey(Map<String, String> labels) {
        if (labels.containsKey("namespace") && labels.containsKey("metric_name") && labels.containsKey("alertname")) {
            return Optional.of(labels.containsKey("namespace") + "_" + labels.get("metric_name") + "_" + labels.get("alertname"));
        }
        return Optional.empty();
    }

    @Override
    public void update() {
        List<MetricFamilySamples> latest = new ArrayList<>();
        if (alarmLabels.size() > 0) {
            List<MetricFamilySamples.Sample> metrics = new ArrayList<>();
            alarmLabels.values().forEach(labels -> {
                Instant metricVal = Instant.now();
                if (labels.containsKey("timestamp")) {
                    metricVal = timeWindowBuilder.getTimeStampInstant(labels.get("timestamp"));
                    labels.remove("timestamp");
                } else {
                    metricVal = timeWindowBuilder.getRegionInstant(labels.get("region"));
                }
                metrics.add(sampleBuilder.buildSingleSample("aws_cloudwatch_alarm", labels,
                        (double) metricVal.getEpochSecond()));
            });
            latest.add(sampleBuilder.buildFamily(metrics));
        }
        metrics = latest;
    }

    @Override
    public List<MetricFamilySamples> collect() {
        update();
        return metrics;
    }
}
