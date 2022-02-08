/*
 *  Copyright © 2022.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.cloudwatch.alarms;

import ai.asserts.aws.exporter.MetricProvider;
import ai.asserts.aws.exporter.MetricSampleBuilder;
import io.prometheus.client.Collector;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import static ai.asserts.aws.MetricNameUtil.SCRAPE_REGION_LABEL;

@Component
@Slf4j
public class AlarmMetricConverter extends Collector implements MetricProvider {

    private final MetricSampleBuilder sampleBuilder;
    private volatile List<MetricFamilySamples> metrics = new ArrayList<>();
    private Map<String, MetricFamilySamples.Sample> samplesMap = new TreeMap<>();

    public AlarmMetricConverter(MetricSampleBuilder sampleBuilder) {
        this.sampleBuilder = sampleBuilder;
    }

    public boolean convertAlarm(AlarmStateChanged alarmStateChanged) {
        AtomicBoolean status = new AtomicBoolean(false);
        SortedMap<String, String> labels = new TreeMap<>();
        if (alarmStateChanged.getDetail() != null &&
                "ALARM".equals(alarmStateChanged.getDetail().getState().getValue())) {
            labels.put(SCRAPE_REGION_LABEL, alarmStateChanged.getRegion());
            String alarmName = alarmStateChanged.getDetail().getAlarmName();
            labels.put("alertname", alarmName);
            labels.put("alertstate", "firing");
            labels.put("alertgroup", "aws_exporter");
            labels.put("asserts_alert_category", "error");
            labels.put("asserts_severity", "warning");
            if (alarmStateChanged.getSource() != null) {
                labels.put("asserts_source", alarmStateChanged.getSource());
            }
            if (isConfigMetricAvailable(alarmStateChanged)) {
                List<Map<String, String>> fieldsValue = getDimensionFields(alarmStateChanged);
                if (!CollectionUtils.isEmpty(fieldsValue)) {
                    fieldsValue.forEach(fields -> {
                        if (fields.containsKey("namespace")) {
                            labels.put("namespace", fields.get("namespace"));
                        }
                        if (fields.containsKey("service")) {
                            String serviceName = fields.get("service");
                            labels.put("service", serviceName);
                            labels.put("job", serviceName);
                            labels.put("asserts_entity_type", "Service");
                            samplesMap.put(serviceName + "_" + alarmName,
                                    sampleBuilder.buildSingleSample("ALERTS", labels, 1.0));
                            log.info("Adding ALERTS for {}", serviceName + "_" + alarmName);
                            status.set(true);

                        }
                    });

                }
            }
        } else if (alarmStateChanged.getDetail() != null) {
            String alarmName = alarmStateChanged.getDetail().getAlarmName();
            if (isConfigMetricAvailable(alarmStateChanged)) {
                List<Map<String, String>> fieldsValue = getDimensionFields(alarmStateChanged);
                if (!CollectionUtils.isEmpty(fieldsValue)) {
                    fieldsValue.forEach(fields -> {
                        if (alarmName != null && fields.containsKey("service")) {
                            String serviceAlert = fields.get("service") + "_" + alarmName;
                            if (samplesMap.containsKey(serviceAlert)) {
                                samplesMap.remove(serviceAlert);
                                log.info("Stopping ALERTS for {}", serviceAlert);
                            }
                            status.set(true);
                        }
                    });

                }
            }
        }
        if (!status.get() && !CollectionUtils.isEmpty(alarmStateChanged.getResources())) {
            log.error("Unable to process Alarms - {}", String.join(",", alarmStateChanged.getResources()));
        }
        return status.get();
    }

    private List<Map<String, String>> getDimensionFields(AlarmStateChanged alarmStateChanged) {
        return alarmStateChanged.getDetail().
                getConfiguration().getMetrics().
                stream().map(this::extractFields)
                .collect(Collectors.toList());
    }

    private boolean isConfigMetricAvailable(AlarmStateChanged alarmStateChanged) {
        return alarmStateChanged.getDetail().getConfiguration() != null &&
                alarmStateChanged.getDetail().getConfiguration().getMetrics() != null;
    }

    private Map<String, String> extractFields(AlarmMetrics metric) {
        Map<String, String> fieldValues = new TreeMap<>();
        if (metric.getMetricStat() != null && metric.getMetricStat().getMetric() != null) {
            AlarmMetric alarmMetric = metric.getMetricStat().getMetric();
            if (alarmMetric.getNamespace() != null) {
                fieldValues.put("namespace", alarmMetric.getNamespace());
            }
            if (!CollectionUtils.isEmpty(alarmMetric.getDimensions())) {
                String job = alarmMetric.getDimensions().values().iterator().next();
                fieldValues.put("service", job);
            }
        }
        return fieldValues;
    }

    @Override
    public void update() {
        List<MetricFamilySamples> latest = new ArrayList<>();
        if (samplesMap.size() > 0) {
            latest.add(sampleBuilder.buildFamily(new ArrayList<>(samplesMap.values())));
        }
        metrics = latest;
    }

    @Override
    public List<MetricFamilySamples> collect() {
        return metrics;
    }
}
