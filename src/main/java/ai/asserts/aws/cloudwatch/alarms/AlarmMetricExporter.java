/*
 *  Copyright © 2022.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.cloudwatch.alarms;

import ai.asserts.aws.exporter.BasicMetricCollector;
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
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class AlarmMetricExporter extends Collector {

    private final MetricSampleBuilder sampleBuilder;
    private final BasicMetricCollector basicMetricCollector;
    @Getter
    private Map<String, Map<String, String>> alarmLabels = new ConcurrentHashMap<>();

    public AlarmMetricExporter(MetricSampleBuilder sampleBuilder,
                               BasicMetricCollector basicMetricCollector) {
        this.sampleBuilder = sampleBuilder;
        this.basicMetricCollector = basicMetricCollector;
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
            return Optional.of(labels.get("namespace") + "_" + labels.get("metric_name") + "_" + labels.get("alertname"));
        }
        return Optional.empty();
    }

    @Override
    public List<MetricFamilySamples> collect() {
        List<MetricFamilySamples> latest = new ArrayList<>();
        if (alarmLabels.size() > 0) {
            List<MetricFamilySamples.Sample> metrics1 = new ArrayList<>();
            alarmLabels.values().forEach(labels -> {
                long timeVal;
                if (labels.containsKey("timestamp")) {
                    Instant timestamp = Instant.parse(labels.get("timestamp"));
                    if (timestamp.compareTo(Instant.now().minusSeconds(30)) < 0) {
                        recordHistogram(labels, timestamp);
                        timeVal = Instant.now().minusSeconds(30).getEpochSecond();
                    } else {
                        timeVal = timestamp.getEpochSecond();
                    }
                    labels.remove("timestamp");
                } else {
                    timeVal = Instant.now().getEpochSecond();
                }
                metrics1.add(sampleBuilder.buildSingleSample("aws_cloudwatch_alarm", labels,
                        1.0, timeVal));
            });
            latest.add(sampleBuilder.buildFamily(metrics1));
        }
        return latest;
    }

    private void recordHistogram(Map<String, String> labels, Instant timestamp) {
        SortedMap<String, String> histoLabels = new TreeMap<>();
        histoLabels.put("namespace", labels.get("namespace"));
        histoLabels.put("region", labels.get("region"));
        histoLabels.put("alertname", labels.get("alertname"));
        long diff = Instant.now().minusSeconds(timestamp.getEpochSecond()).getEpochSecond();
        this.basicMetricCollector.recordHistogram("aws_cloudwatch_alarm", histoLabels, diff);
    }
}
