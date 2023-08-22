/*
 *  Copyright © 2021.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.cloudwatch.alarms;

import ai.asserts.aws.AWSClientProvider;
import ai.asserts.aws.CollectionBuilderTask;
import ai.asserts.aws.AWSApiCallRateLimiter;
import ai.asserts.aws.ScrapeConfigProvider;
import ai.asserts.aws.TaskExecutorUtil;
import ai.asserts.aws.EnvironmentConfig;
import ai.asserts.aws.account.AWSAccount;
import ai.asserts.aws.account.AccountProvider;
import ai.asserts.aws.config.ScrapeConfig;
import ai.asserts.aws.exporter.ECSServiceDiscoveryExporter;
import ai.asserts.aws.exporter.MetricSampleBuilder;
import com.google.common.collect.ImmutableSortedMap;
import io.prometheus.client.Collector;
import io.prometheus.client.Collector.MetricFamilySamples.Sample;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

import static ai.asserts.aws.MetricNameUtil.SCRAPE_ACCOUNT_ID_LABEL;
import static ai.asserts.aws.MetricNameUtil.SCRAPE_OPERATION_LABEL;
import static ai.asserts.aws.MetricNameUtil.SCRAPE_REGION_LABEL;

@Component
@Slf4j
public class AlarmFetcher extends Collector implements InitializingBean {
    public final CollectorRegistry collectorRegistry;
    private final AccountProvider accountProvider;
    private final AWSApiCallRateLimiter rateLimiter;
    private final AWSClientProvider awsClientProvider;
    private final AlarmMetricConverter alarmMetricConverter;
    private final MetricSampleBuilder sampleBuilder;
    private final ScrapeConfigProvider scrapeConfigProvider;
    private final ECSServiceDiscoveryExporter ecsServiceDiscoveryExporter;
    private final TaskExecutorUtil taskExecutorUtil;
    private final EnvironmentConfig environmentConfig;
    private volatile List<MetricFamilySamples> metricFamilySamples = new ArrayList<>();

    public AlarmFetcher(AccountProvider accountProvider,
                        AWSClientProvider awsClientProvider,
                        CollectorRegistry collectorRegistry,
                        AWSApiCallRateLimiter rateLimiter,
                        MetricSampleBuilder sampleBuilder,
                        AlarmMetricConverter alarmMetricConverter,
                        ScrapeConfigProvider scrapeConfigProvider,
                        ECSServiceDiscoveryExporter ecsServiceDiscoveryExporter,
                        TaskExecutorUtil taskExecutorUtil,
                        EnvironmentConfig environmentConfig) {
        this.accountProvider = accountProvider;
        this.awsClientProvider = awsClientProvider;
        this.collectorRegistry = collectorRegistry;
        this.rateLimiter = rateLimiter;
        this.sampleBuilder = sampleBuilder;
        this.alarmMetricConverter = alarmMetricConverter;
        this.scrapeConfigProvider = scrapeConfigProvider;
        this.ecsServiceDiscoveryExporter = ecsServiceDiscoveryExporter;
        this.taskExecutorUtil = taskExecutorUtil;
        this.environmentConfig = environmentConfig;
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
        if (environmentConfig.isSingleTenant() && environmentConfig.isSingleInstance() && !ecsServiceDiscoveryExporter.isPrimaryExporter()) {
            log.info("Not primary exporter. Skip fetching CloudWatch alarms");
            return;
        }
        List<MetricFamilySamples> newFamily = new ArrayList<>();
        List<Sample> allSamples = new ArrayList<>();
        List<Future<List<Sample>>> futures = new ArrayList<>();
        log.info("Start Fetching alarms");
        for (AWSAccount accountRegion : accountProvider.getAccounts()) {
            ScrapeConfig scrapeConfig = scrapeConfigProvider.getScrapeConfig(accountRegion.getTenant());
            if (!scrapeConfig.isPullCWAlarms()) {
                continue;
            }
            accountRegion.getRegions().forEach(region ->
                    futures.add(taskExecutorUtil.executeAccountTask(accountRegion,
                            new CollectionBuilderTask<Sample>() {
                                @Override
                                public List<Sample> call() {
                                    log.info("Fetching alarms from account {} and region {}",
                                            accountRegion.getAccountId(),
                                            region);
                                    List<Map<String, String>> labelsList = getAlarms(accountRegion, region);
                                    labelsList.forEach(alarmMetricConverter::simplifyAlarmName);
                                    return labelsList.stream()
                                            .map(labels -> {
                                                labels.remove("timestamp");
                                                return sampleBuilder.buildSingleSample(
                                                        "aws_cloudwatch_alarm", labels, 1.0);
                                            })
                                            .filter(Optional::isPresent)
                                            .map(Optional::get).collect(Collectors.toList());
                                }
                            })));
        }
        taskExecutorUtil.awaitAll(futures, allSamples::addAll);
        if (allSamples.size() > 0) {
            sampleBuilder.buildFamily(allSamples).ifPresent(newFamily::add);
        }
        metricFamilySamples = newFamily;
        log.info("Exported {} alarms as metrics", allSamples.size());
    }

    private List<Map<String, String>> getAlarms(AWSAccount account, String region) {
        List<Map<String, String>> labelsList = new ArrayList<>();
        String[] nextToken = new String[]{null};
        try {
            CloudWatchClient cloudWatchClient = awsClientProvider.getCloudWatchClient(region, account);
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
            log.error("Failed to fetch CloudWatch alarms", e);
        }
        log.info("Fetched {} alarms from CloudWatch", labelsList.size());
        return labelsList;
    }

    private Map<String, String> processMetricAlarm(MetricAlarm alarm, String accountId, String region) {
        Map<String, String> labels = new TreeMap<>();
        labels.put(SCRAPE_REGION_LABEL, region);
        labels.put("state", alarm.stateValueAsString());
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
        labels.put("timestamp", alarm.stateUpdatedTimestamp().toString());

        // If this is an alarm on ECS Service, set the service name as the `workload`
        if ("AWS/ECS".equals(alarm.namespace()) &&
                alarm.hasDimensions() && alarm.dimensions().stream().anyMatch(d -> d.name().equals("ServiceName"))) {
            alarm.dimensions().stream()
                    .filter(d -> d.name().equals("ServiceName"))
                    .findFirst()
                    .ifPresent(d -> labels.put("workload", d.value()));
        } else {
            // We don't want the alarm to automatically get attached to the `exporter` as the target label
            // will add `workload=exporter` since the metrics are being scraped from the exporter.
            labels.put("workload", "none");
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
