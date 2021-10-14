/*
 * Copyright © 2021
 * Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.lambda;

import ai.asserts.aws.AWSClientProvider;
import ai.asserts.aws.MetricNameUtil;
import ai.asserts.aws.cloudwatch.config.LogScrapeConfig;
import ai.asserts.aws.cloudwatch.config.NamespaceConfig;
import ai.asserts.aws.cloudwatch.config.ScrapeConfig;
import ai.asserts.aws.cloudwatch.config.ScrapeConfigProvider;
import ai.asserts.aws.cloudwatch.prometheus.GaugeExporter;
import ai.asserts.aws.resource.Resource;
import ai.asserts.aws.resource.TagFilterResourceProvider;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableMap;
import io.micrometer.core.instrument.util.StringUtils;
import lombok.EqualsAndHashCode;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;
import software.amazon.awssdk.services.cloudwatchlogs.model.FilterLogEventsRequest;
import software.amazon.awssdk.services.cloudwatchlogs.model.FilterLogEventsResponse;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TimerTask;

import static ai.asserts.aws.MetricNameUtil.SELF_FUNCTION_NAME_LABEL;
import static ai.asserts.aws.MetricNameUtil.SELF_LATENCY_METRIC;
import static ai.asserts.aws.MetricNameUtil.SELF_OPERATION_LABEL;
import static ai.asserts.aws.MetricNameUtil.SELF_REGION_LABEL;
import static java.lang.String.format;

/**
 * Scrapes the cloudwatch logs and converts log messages into <code>aws_lambda_logs</code> metrics based
 * on the provided {@link LogScrapeConfig}s
 */
@Slf4j
@EqualsAndHashCode(callSuper = false, onlyExplicitlyIncluded = true)
@Setter
public class LambdaLogMetricScrapeTask extends TimerTask {
    @EqualsAndHashCode.Include
    private final String region;
    private final List<LogScrapeConfig> logScrapeConfigs;
    @Autowired
    private AWSClientProvider awsClientProvider;
    @Autowired
    private ScrapeConfigProvider scrapeConfigProvider;
    @Autowired
    private LambdaFunctionScraper lambdaFunctionScraper;
    @Autowired
    private TagFilterResourceProvider tagFilterResourceProvider;
    @Autowired
    private GaugeExporter gaugeExporter;
    @Autowired
    private MetricNameUtil metricNameUtil;

    public LambdaLogMetricScrapeTask(String region, List<LogScrapeConfig> logScrapeConfigs) {
        this.region = region;
        this.logScrapeConfigs = logScrapeConfigs;
    }

    public void run() {
        Instant endTime = now().minusSeconds(60);
        Instant startTime = endTime.minusSeconds(60);

        log.info("BEGIN lambda log scrape for region {}", region);
        ScrapeConfig scrapeConfig = scrapeConfigProvider.getScrapeConfig();
        Optional<NamespaceConfig> lambdaNS = scrapeConfig.getLambdaConfig();
        try {
            if (!lambdaFunctionScraper.getFunctions().containsKey(region)) {
                log.info("No functions found for region {}", region);
                return;
            }
            CloudWatchLogsClient cloudWatchLogsClient = awsClientProvider.getCloudWatchLogsClient(region);
            Set<Resource> functionResources = lambdaNS.isPresent() ?
                    tagFilterResourceProvider.getFilteredResources(region, lambdaNS.get()) : Collections.emptySet();

            lambdaFunctionScraper.getFunctions().get(region).forEach((arn, functionConfig) -> logScrapeConfigs.stream()
                    .filter(logScrapeConfig -> logScrapeConfig.shouldScrapeLogsFor(functionConfig.getName()))
                    .findFirst()
                    .ifPresent(logScrapeConfig -> scrapeLogs(endTime, startTime, cloudWatchLogsClient,
                            functionResources, functionConfig, logScrapeConfig)));
        } catch (Exception e) {
            log.error("Failed to scrape lambda logs", e);
        }
        log.info("END lambda log scrape for region {}", region);
    }

    private void scrapeLogs(Instant endTime, Instant startTime, CloudWatchLogsClient cloudWatchLogsClient,
                            Set<Resource> functionResources, LambdaFunction functionConfig,
                            LogScrapeConfig logScrapeConfig) {
        String nextToken = null;
        String logGroupName = format("/aws/lambda/%s", functionConfig.getName());
        log.info("About to scrape logs from {}", logGroupName);
        try {
            do {
                FilterLogEventsRequest logEventsRequest = FilterLogEventsRequest.builder()
                        .limit(10)
                        .startTime(startTime.toEpochMilli())
                        .endTime(endTime.toEpochMilli())
                        .filterPattern(logScrapeConfig.getLogFilterPattern())
                        .logGroupName(logGroupName)
                        .nextToken(nextToken)
                        .build();

                long timeTaken = System.currentTimeMillis();
                FilterLogEventsResponse response = cloudWatchLogsClient.filterLogEvents(logEventsRequest);
                timeTaken = System.currentTimeMillis() - timeTaken;
                captureLatency(functionConfig, timeTaken);

                if (response.hasEvents()) {
                    Set<Map<String, String>> uniqueLabels = new LinkedHashSet<>();
                    log.info("log scrape config {} matched {} events", logScrapeConfig, response.events().size());
                    response.events().forEach(filteredLogEvent -> {
                        Map<String, String> logLabels = logScrapeConfig.extractLabels(filteredLogEvent.message());
                        if (logLabels.size() > 0) {
                            logLabels.put("region", region);
                            logLabels.put("d_function_name", functionConfig.getName());
                            functionResources.stream()
                                    .filter(resource ->
                                            resource.getArn().equals(functionConfig.getArn()))
                                    .findFirst()
                                    .ifPresent(resource ->
                                            logLabels.putAll(metricNameUtil.getResourceTagLabels(resource)));
                            uniqueLabels.add(logLabels);
                        }
                    });

                    uniqueLabels.forEach(labels ->
                            gaugeExporter.exportMetric("aws_lambda_logs", "", labels, endTime,
                                    1.0D));
                }
                nextToken = response.nextToken();
            } while (!StringUtils.isEmpty(nextToken));
        } catch (Exception e) {
            log.error("Failed to scrape logs from log group " + logGroupName, e);
        }
    }

    private void captureLatency(LambdaFunction functionConfig, long timeTaken) {
        gaugeExporter.exportMetric(SELF_LATENCY_METRIC, "scraper Instrumentation",
                ImmutableMap.of(
                        SELF_REGION_LABEL, region,
                        SELF_OPERATION_LABEL, "scrape_lambda_logs",
                        SELF_FUNCTION_NAME_LABEL, functionConfig.getName()
                ), Instant.now(), timeTaken * 1.0D);
    }

    @VisibleForTesting
    Instant now() {
        return Instant.now();
    }
}
