/*
 *  Copyright © 2021.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.cloudwatch.alarms;

import ai.asserts.aws.AWSClientProvider;
import ai.asserts.aws.AccountProvider;
import ai.asserts.aws.AccountProvider.AWSAccount;
import ai.asserts.aws.MetricNameUtil;
import ai.asserts.aws.RateLimiter;
import ai.asserts.aws.ScrapeConfigProvider;
import ai.asserts.aws.exporter.BasicMetricCollector;
import ai.asserts.aws.exporter.MetricSampleBuilder;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableSortedMap;
import io.prometheus.client.Collector;
import io.prometheus.client.CollectorRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.ComparisonOperator;
import software.amazon.awssdk.services.cloudwatch.model.DescribeAlarmsRequest;
import software.amazon.awssdk.services.cloudwatch.model.DescribeAlarmsResponse;
import software.amazon.awssdk.services.cloudwatch.model.MetricAlarm;
import software.amazon.awssdk.services.cloudwatch.model.StateValue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.stream.Collectors;

import static ai.asserts.aws.MetricNameUtil.SCRAPE_ACCOUNT_ID_LABEL;
import static ai.asserts.aws.MetricNameUtil.SCRAPE_OPERATION_LABEL;
import static ai.asserts.aws.MetricNameUtil.SCRAPE_REGION_LABEL;

@Component
@Slf4j
public class AlarmFetcher extends Collector implements InitializingBean {
    public final CollectorRegistry collectorRegistry;
    private final AccountProvider accountProvider;
    private final RateLimiter rateLimiter;
    private final AWSClientProvider awsClientProvider;
    private final AlarmMetricConverter alarmMetricConverter;
    private final MetricSampleBuilder sampleBuilder;
    private final BasicMetricCollector basicMetricCollector;
    private final ScrapeConfigProvider scrapeConfigProvider;
    private volatile List<MetricFamilySamples> metricFamilySamples = new ArrayList<>();

    public AlarmFetcher(AccountProvider accountProvider,
                        AWSClientProvider awsClientProvider,
                        CollectorRegistry collectorRegistry,
                        RateLimiter rateLimiter,
                        MetricSampleBuilder sampleBuilder,
                        BasicMetricCollector basicMetricCollector,
                        AlarmMetricConverter alarmMetricConverter,
                        ScrapeConfigProvider scrapeConfigProvider) {
        this.accountProvider = accountProvider;
        this.awsClientProvider = awsClientProvider;
        this.collectorRegistry = collectorRegistry;
        this.rateLimiter = rateLimiter;
        this.sampleBuilder = sampleBuilder;
        this.basicMetricCollector = basicMetricCollector;
        this.alarmMetricConverter = alarmMetricConverter;
        this.scrapeConfigProvider = scrapeConfigProvider;
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        register(collectorRegistry);
    }

    @Override
    public List<MetricFamilySamples> collect() {
        return metricFamilySamples;
    }

    public void update() {
        if (!scrapeConfigProvider.getScrapeConfig().isPullCWAlarms()) {
            return;
        }
        List<MetricFamilySamples> newFamily = new ArrayList<>();
        List<MetricFamilySamples.Sample> samples = new ArrayList<>();
        for (AWSAccount accountRegion : accountProvider.getAccounts()) {
            accountRegion.getRegions().forEach(region -> {
                log.info("Fetching alarms from account {} and region {}", accountRegion.getAccountId(), region);
                List<Map<String, String>> labelsList = getAlarms(accountRegion, region);
                samples.addAll(labelsList.stream()
                        .map(labels -> {
                                    if (labels.containsKey("timestamp")) {
                                        Instant timestamp = Instant.parse(labels.get("timestamp"));
                                        recordHistogram(labels, timestamp);
                                        labels.remove("timestamp");
                                    }
                                    return sampleBuilder.buildSingleSample("aws_cloudwatch_alarm", labels,
                                            1.0);
                                }
                        )
                        .collect(Collectors.toList()));

            });
        }
        newFamily.add(sampleBuilder.buildFamily(samples));
        metricFamilySamples = newFamily;
    }

    private void recordHistogram(Map<String, String> labels, Instant timestamp) {
        SortedMap<String, String> histoLabels = new TreeMap<>();
        histoLabels.put("namespace", labels.get("namespace"));
        histoLabels.put("account_id", labels.get("account_id"));
        histoLabels.put("region", labels.get("region"));
        histoLabels.put("alertname", labels.get("alertname"));
        long diff = (now().toEpochMilli() - timestamp.toEpochMilli()) / 1000;
        this.basicMetricCollector.recordHistogram(MetricNameUtil.EXPORTER_DELAY_SECONDS, histoLabels, diff);
    }

    private List<Map<String, String>> getAlarms(AWSAccount account, String region) {
        List<Map<String, String>> labelsList = new ArrayList<>();
        String[] nextToken = new String[]{null};
        try (CloudWatchClient cloudWatchClient = awsClientProvider.getCloudWatchClient(region, account)) {
            do {
                DescribeAlarmsResponse response = rateLimiter.doWithRateLimit(
                        "CloudWatchClient/describeAlarms",
                        ImmutableSortedMap.of(
                                SCRAPE_ACCOUNT_ID_LABEL, account.getAccountId(),
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
                            .map(metricAlarm -> this.processMetricAlarm(metricAlarm, account.getAccountId(), region))
                            .collect(Collectors.toList()));
                }
                // TODO Handle Composite Alarms
                nextToken[0] = response.nextToken();
            } while (nextToken[0] != null);
        } catch (Exception e) {
            log.error("Failed to build resource metric samples", e);
        }
        return labelsList;
    }

    private Map<String, String> processMetricAlarm(MetricAlarm alarm, String accountId, String region) {
        Map<String, String> labels = new TreeMap<>();
        labels.put("alertname", alarm.alarmName());
        labels.put(SCRAPE_REGION_LABEL, region);
        labels.put("state", alarm.stateValueAsString());
        labels.put("timestamp", alarm.stateUpdatedTimestamp().toString());
        labels.put("threshold", Double.toString(alarm.threshold()));
        labels.put("namespace", alarm.namespace());
        labels.put("metric_namespace", alarm.namespace());
        labels.put(SCRAPE_ACCOUNT_ID_LABEL, accountId);
        if (alarm.metricName() != null) {
            labels.put("metric_name", alarm.metricName());
        }
        if (alarm.comparisonOperatorAsString() != null) {
            labels.put("metric_operator", mapComparisonOperator(alarm.comparisonOperator()));
        }
        labels.putAll(alarmMetricConverter.extractMetricAndEntityLabels(alarm));
        if (alarm.hasDimensions()) {
            alarm.dimensions().forEach(dimension -> labels.put("d_" + dimension.name(), dimension.value()));
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

    @VisibleForTesting
    Instant now() {
        return Instant.now();
    }
}
