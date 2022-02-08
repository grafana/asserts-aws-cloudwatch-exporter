/*
 *  Copyright © 2022.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.cloudwatch.alarms;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.stream.Collectors;

import static ai.asserts.aws.MetricNameUtil.SCRAPE_REGION_LABEL;

@Component
@Slf4j
public class AlarmMetricConverter {

    public List<Map<String, String>> convertAlarm(AlarmStateChange alarmStateChange) {
        List<Map<String, String>> labelsList = new ArrayList<>();
        if (alarmStateChange.getDetail() != null &&
                "ALARM".equals(alarmStateChange.getDetail().getState().getValue())) {
            if (isConfigMetricAvailable(alarmStateChange)) {
                List<Map<String, String>> fieldsValue = getDimensionFields(alarmStateChange);
                if (!CollectionUtils.isEmpty(fieldsValue)) {
                    fieldsValue.forEach(values -> {
                        SortedMap<String, String> labels = new TreeMap<>();
                        labels.put(SCRAPE_REGION_LABEL, alarmStateChange.getRegion());
                        String alarmName = alarmStateChange.getDetail().getAlarmName();
                        labels.put("alertname", alarmName);
                        labels.put("state", alarmStateChange.getDetail().getState().getValue());
                        labels.put("timestamp", alarmStateChange.getTime());
                        labels.putAll(values);
                        labelsList.add(labels);
                    });
                }
            }
        } else if (alarmStateChange.getDetail() != null) {
            String alarmName = alarmStateChange.getDetail().getAlarmName();
            if (isConfigMetricAvailable(alarmStateChange)) {
                List<Map<String, String>> fieldsValue = getDimensionFields(alarmStateChange);
                if (!CollectionUtils.isEmpty(fieldsValue)) {
                    fieldsValue.forEach(fields -> {
                        SortedMap<String, String> labels = new TreeMap<>();
                        labels.put("alertname", alarmName);
                        labels.put("state", alarmStateChange.getDetail().getState().getValue());
                        labels.putAll(fields);
                        labelsList.add(labels);
                    });
                }
            }
        } else {
            log.error("Unable to process Alarms - {}", String.join(",", alarmStateChange.getResources()));
        }
        return labelsList;
    }

    private List<Map<String, String>> getDimensionFields(AlarmStateChange alarmStateChange) {
        return alarmStateChange.getDetail().
                getConfiguration().getMetrics().
                stream().map(this::extractFields)
                .flatMap(Collection::stream)
                .collect(Collectors.toList());
    }

    private boolean isConfigMetricAvailable(AlarmStateChange alarmStateChange) {
        return alarmStateChange.getDetail().getConfiguration() != null &&
                alarmStateChange.getDetail().getConfiguration().getMetrics() != null;
    }

    private List<Map<String, String>> extractFields(AlarmMetrics metric) {
        List<Map<String, String>> fieldValues = new ArrayList<>();
        if (metric.getMetricStat() != null && metric.getMetricStat().getMetric() != null) {
            AlarmMetric alarmMetric = metric.getMetricStat().getMetric();

            if (!CollectionUtils.isEmpty(alarmMetric.getDimensions())) {
                alarmMetric.getDimensions().forEach((key, value) -> {
                    Map<String, String> fields = new TreeMap<>();
                    fields.put(key, value);
                    if (alarmMetric.getNamespace() != null) {
                        fields.put("namespace", alarmMetric.getNamespace());
                    }
                    if (metric.getMetricStat().getStat() != null) {
                        fields.put("metric_stat", metric.getMetricStat().getStat());
                    }
                    if (metric.getMetricStat().getUnit() != null) {
                        fields.put("metric_unit", metric.getMetricStat().getUnit());
                    }
                    if (alarmMetric.getName() != null) {
                        fields.put("metric_name", alarmMetric.getName());
                    }
                    fieldValues.add(fields);
                });
            }
        }
        return fieldValues;
    }
}
