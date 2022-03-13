/*
 *  Copyright © 2021.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.cloudwatch.alarms;

import ai.asserts.aws.AWSClientProvider;
import ai.asserts.aws.RateLimiter;
import ai.asserts.aws.cloudwatch.config.ScrapeConfig;
import ai.asserts.aws.cloudwatch.config.ScrapeConfigProvider;
import ai.asserts.aws.exporter.AccountIDProvider;
import com.google.common.collect.ImmutableSortedMap;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.ComparisonOperator;
import software.amazon.awssdk.services.cloudwatch.model.DescribeAlarmsRequest;
import software.amazon.awssdk.services.cloudwatch.model.DescribeAlarmsResponse;
import software.amazon.awssdk.services.cloudwatch.model.MetricAlarm;
import software.amazon.awssdk.services.cloudwatch.model.StateValue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import static ai.asserts.aws.MetricNameUtil.SCRAPE_ACCOUNT_ID_LABEL;
import static ai.asserts.aws.MetricNameUtil.SCRAPE_OPERATION_LABEL;
import static ai.asserts.aws.MetricNameUtil.SCRAPE_REGION_LABEL;

@Component
@Slf4j
@AllArgsConstructor
public class AlarmFetcher {
    private final AccountIDProvider accountIDProvider;
    private final RateLimiter rateLimiter;
    private final AWSClientProvider awsClientProvider;
    private final ScrapeConfigProvider scrapeConfigProvider;
    private final AlertsProcessor alertsProcessor;
    private final AlarmMetricConverter alarmMetricConverter;

    public void sendAlarmsForRegions() {
        ScrapeConfig scrapeConfig = scrapeConfigProvider.getScrapeConfig();
        scrapeConfig.getRegions().forEach(region -> {
            List<Map<String, String>> labelsList = getAlarms(region);
            alertsProcessor.sendAlerts(labelsList);
        });
    }

    private List<Map<String, String>> getAlarms(String region) {
        List<Map<String, String>> labelsList = new ArrayList<>();
        String[] nextToken = new String[]{null};
        try (CloudWatchClient cloudWatchClient = awsClientProvider.getCloudWatchClient(region,
                scrapeConfigProvider.getScrapeConfig().getAssumeRole())) {
            do {
                DescribeAlarmsResponse response = rateLimiter.doWithRateLimit(
                        "CloudWatchClient/describeAlarms",
                        ImmutableSortedMap.of(
                                SCRAPE_REGION_LABEL, region,
                                SCRAPE_OPERATION_LABEL, "CloudWatchClient/describeAlarms"
                        ),
                        () -> cloudWatchClient.describeAlarms(DescribeAlarmsRequest.builder()
                                .stateValue(StateValue.ALARM)
                                .nextToken(nextToken[0])
                                .build()));


                if (response.hasMetricAlarms()) {
                    labelsList.addAll(response.metricAlarms()
                            .stream()
                            .map(metricAlarm -> this.processMetricAlarm(metricAlarm, region))
                            .collect(Collectors.toList()));
                }
                if (response.hasCompositeAlarms()) {
                    //TODO: Implement this case
                }
                nextToken[0] = response.nextToken();
            } while (nextToken[0] != null);
        } catch (Exception e) {
            log.error("Failed to build resource metric samples", e);
        }
        return labelsList;
    }

    private Map<String, String> processMetricAlarm(MetricAlarm alarm, String region) {
        Map<String, String> labels = new TreeMap<>();
        labels.put("alertname", alarm.alarmName());
        labels.put(SCRAPE_REGION_LABEL, region);
        labels.put("state", alarm.stateValueAsString());
        labels.put("timestamp", alarm.stateUpdatedTimestamp().toString());
        labels.put("threshold", Double.toString(alarm.threshold()));
        labels.put("namespace", alarm.namespace());
        labels.put("metric_namespace", alarm.namespace());
        labels.put(SCRAPE_ACCOUNT_ID_LABEL, accountIDProvider.getAccountId());
        if (alarm.metricName() != null) {
            labels.put("metric_name", alarm.metricName());
        }
        if (alarm.comparisonOperatorAsString() != null) {
            labels.put("metric_operator", mapComparisonOperator(alarm.comparisonOperator()));
        }
        labels.putAll(alarmMetricConverter.extractMetricAndEntityLabels(alarm));
        if (alarm.hasDimensions()) {
            alarm.dimensions().forEach(dimension -> {
                labels.put("d_" + dimension.name(), dimension.value());
            });
        }
        return labels;
    }

    private String mapComparisonOperator(ComparisonOperator operator) {
        String strOperator = "";
        switch (operator) {
            case LESS_THAN_THRESHOLD:
            case LESS_THAN_LOWER_THRESHOLD:
                strOperator = "<";
                break;
            case GREATER_THAN_THRESHOLD:
            case GREATER_THAN_UPPER_THRESHOLD:
                strOperator = ">";
                break;
            case LESS_THAN_OR_EQUAL_TO_THRESHOLD:
                strOperator = "<=";
                break;
            case GREATER_THAN_OR_EQUAL_TO_THRESHOLD:
                strOperator = ">=";
                break;
            case LESS_THAN_LOWER_OR_GREATER_THAN_UPPER_THRESHOLD:
                strOperator = "> or <";
                break;
        }
        return strOperator;
    }
}
