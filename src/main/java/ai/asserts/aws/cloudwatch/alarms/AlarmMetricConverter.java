/*
 *  Copyright © 2022.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.cloudwatch.alarms;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.stream.Collectors;

import static ai.asserts.aws.MetricNameUtil.SCRAPE_REGION_LABEL;

@Component
@Slf4j
public class AlarmMetricConverter {

    public List<Map<String, String>> convertAlarm(AlarmStateChange alarmStateChange) {
        List<Map<String, String>> labelsList = new ArrayList<>();
        if (alarmStateChange.getDetail() != null) {
            if (isConfigMetricAvailable(alarmStateChange)) {
                List<Map<String, String>> fieldsValue = getDimensionFields(alarmStateChange);
                if (!CollectionUtils.isEmpty(fieldsValue)) {
                    fieldsValue.forEach(values -> {
                        SortedMap<String, String> labels = new TreeMap<>();
                        if (alarmStateChange.getRegion() != null) {
                            labels.put(SCRAPE_REGION_LABEL, alarmStateChange.getRegion());
                        }
                        if (alarmStateChange.getDetail().getAlarmName() != null) {
                            labels.put("alertname", alarmStateChange.getDetail().getAlarmName());
                        }
                        if (alarmStateChange.getDetail().getState() != null
                                && alarmStateChange.getDetail().getState().getValue() != null) {
                            labels.put("state", alarmStateChange.getDetail().getState().getValue());
                        }
                        if (alarmStateChange.getTime() != null) {
                            labels.put("timestamp", alarmStateChange.getTime());
                        }
                        labels.putAll(values);
                        if (alarmStateChange.getDetail().getState().getReasonData() != null) {
                            Optional<String> threshold = parseThreshold(alarmStateChange.getDetail().getState()
                                    .getReasonData());
                            threshold.map(k -> labels.put("threshold", k));
                        }
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
                        if (alarmMetric.getNamespace().equals("AWS/AutoScaling") ||
                                key.equals("AutoScalingGroupName")) {
                            fields.put("namespace", "AWS/AutoScaling");
                            fields.put("AutoScalingGroup", value);
                            fields.put("asserts_entity_type", "AutoScalingGroup");
                        } else {
                            fields.put("job", value);
                            fields.put("asserts_entity_type", "Service");
                        }
                    }

                    fieldValues.add(fields);
                });
            }
        }
        return fieldValues;
    }

    private Optional<String> parseThreshold(String reasonData) {
        JsonElement element = JsonParser.parseString(reasonData);
        if (element.isJsonObject()) {
            JsonObject jsonObject = (JsonObject) element;
            JsonElement ele_threshold = jsonObject.get("threshold");
            if (ele_threshold.isJsonPrimitive()) {
                return Optional.of(ele_threshold.getAsString());
            }
        }
        return Optional.empty();
    }
}
