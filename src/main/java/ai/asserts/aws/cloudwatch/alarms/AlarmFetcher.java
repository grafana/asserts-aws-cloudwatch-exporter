/*
 *  Copyright © 2021.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.cloudwatch.alarms;

import ai.asserts.aws.AWSClientProvider;
import ai.asserts.aws.RateLimiter;
import ai.asserts.aws.cloudwatch.config.ScrapeConfig;
import ai.asserts.aws.cloudwatch.config.ScrapeConfigProvider;
import com.google.common.collect.ImmutableSortedMap;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.DescribeAlarmsRequest;
import software.amazon.awssdk.services.cloudwatch.model.DescribeAlarmsResponse;
import software.amazon.awssdk.services.cloudwatch.model.MetricAlarm;
import software.amazon.awssdk.services.cloudwatch.model.StateValue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import static ai.asserts.aws.MetricNameUtil.SCRAPE_OPERATION_LABEL;
import static ai.asserts.aws.MetricNameUtil.SCRAPE_REGION_LABEL;

@Component
@Slf4j
@AllArgsConstructor
public class AlarmFetcher {

    private final RateLimiter rateLimiter;
    private final AWSClientProvider awsClientProvider;
    private final ScrapeConfigProvider scrapeConfigProvider;
    private final AlertsProcessor alertsProcessor;

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
        try (CloudWatchClient cloudWatchClient = awsClientProvider.getCloudWatchClient(region)) {
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
        if (alarm.hasDimensions()) {
            alarm.dimensions().forEach(dimension -> {
                labels.put("alertname", alarm.alarmName());
                labels.put(SCRAPE_REGION_LABEL, region);
                labels.put("state", alarm.stateValueAsString());
                labels.put("timestamp", alarm.stateUpdatedTimestamp().toString());
                labels.put("threshold", Double.toString(alarm.threshold()));
                labels.put("namespace", alarm.namespace());
                //TODO: Needs mapping yaml to handle this
                if (alarm.namespace().equals("AWS/AutoScaling") ||
                        dimension.name().equals("AutoScalingGroupName")) {
                    labels.put("namespace", "AWS/AutoScaling");
                    labels.put("AutoScalingGroup", dimension.value());
                    labels.put("asserts_entity_type", "AutoScalingGroup");
                } else {
                    labels.put("job", dimension.value());
                    labels.put("asserts_entity_type", "Service");
                }
            });
        }
        return labels;
    }
}
