/*
 *  Copyright © 2022.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.cloudwatch.alarms;

import ai.asserts.aws.cloudwatch.config.ScrapeConfig;
import ai.asserts.aws.cloudwatch.config.ScrapeConfigProvider;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import software.amazon.awssdk.services.cloudwatch.model.MetricAlarm;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.stream.Collectors;

import static ai.asserts.aws.MetricNameUtil.SCRAPE_ACCOUNT_ID_LABEL;
import static ai.asserts.aws.MetricNameUtil.SCRAPE_REGION_LABEL;
import static org.springframework.util.StringUtils.hasLength;

@Component
@Slf4j
@AllArgsConstructor
public class AlarmMetricConverter {
    private final ScrapeConfigProvider scrapeConfigProvider;

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
                        if (alarmStateChange.getAccount() != null) {
                            labels.put(SCRAPE_ACCOUNT_ID_LABEL, alarmStateChange.getAccount());
                        }
                        if (alarmStateChange.getDetail().getAlarmName() != null) {
                            labels.put("alarm_name", alarmStateChange.getDetail().getAlarmName());
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

    Map<String, String> extractMetricAndEntityLabels(MetricAlarm metric) {
        Map<String, String> labels = new TreeMap<>();

        labels.put("alarm_name", metric.alarmName());
        labels.put("namespace", metric.namespace());
        labels.put("metric_namespace", metric.namespace());
        labels.put("metric_name", metric.metricName());
        labels.put("metric_stat", metric.statisticAsString());
        labels.put("metric_period", Integer.toString(metric.period()));

        ScrapeConfig scrapeConfig = scrapeConfigProvider.getScrapeConfig();

        SortedMap<String, String> dimensionAsMap = new TreeMap<>();
        metric.dimensions().forEach(d -> dimensionAsMap.put(d.name(), d.value()));
        labels.putAll(scrapeConfig.getEntityLabels(metric.namespace(), dimensionAsMap));

        dimensionAsMap.forEach((key, value) -> labels.put("d_" + key, value));
        return labels;
    }

    private List<Map<String, String>> getDimensionFields(AlarmStateChange alarmStateChange) {
        return alarmStateChange.getDetail().
                getConfiguration().getMetrics().
                stream().map(this::extractMetricAndEntityLabels)
                .collect(Collectors.toList());
    }

    private boolean isConfigMetricAvailable(AlarmStateChange alarmStateChange) {
        return alarmStateChange.getDetail().getConfiguration() != null &&
                alarmStateChange.getDetail().getConfiguration().getMetrics() != null;
    }

    private Map<String, String> extractMetricAndEntityLabels(AlarmMetrics metric) {
        Map<String, String> labels = new TreeMap<>();
        AlarmMetricStat metricStat = metric.getMetricStat();
        if (metricStat != null && metricStat.getMetric() != null) {
            AlarmMetric alarmMetric = metricStat.getMetric();

            if (alarmMetric.getNamespace() != null) {
                labels.put("namespace", alarmMetric.getNamespace());
            }

            if (alarmMetric.getName() != null) {
                labels.put("metric_name", alarmMetric.getName());
            }

            if (metricStat.getStat() != null) {
                labels.put("metric_stat", metricStat.getStat());
            }

            if (metricStat.getPeriod() != null) {
                labels.put("metric_period", metricStat.getPeriod().toString());
            }

            if (hasLength(alarmMetric.getNamespace()) && hasLength(metricStat.getMetric().getName())) {
                ScrapeConfig scrapeConfig = scrapeConfigProvider.getScrapeConfig();
                labels.putAll(scrapeConfig.getEntityLabels(alarmMetric.getNamespace(), alarmMetric.getDimensions()));
            }

            if (!CollectionUtils.isEmpty(alarmMetric.getDimensions())) {
                alarmMetric.getDimensions().forEach((key, value) -> labels.put("d_" + key, value));
            }
        }
        return labels;
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
