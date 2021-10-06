/*
 * Copyright © 2021
 * Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.lambda;

import ai.asserts.aws.AWSClientProvider;
import ai.asserts.aws.cloudwatch.config.LogScrapeConfig;
import ai.asserts.aws.cloudwatch.prometheus.GaugeExporter;
import com.google.common.annotations.VisibleForTesting;
import lombok.EqualsAndHashCode;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;
import software.amazon.awssdk.services.cloudwatchlogs.model.FilterLogEventsRequest;
import software.amazon.awssdk.services.cloudwatchlogs.model.FilterLogEventsResponse;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimerTask;

import static java.lang.String.format;

@Slf4j
@EqualsAndHashCode(callSuper = false, onlyExplicitlyIncluded = true)
@Setter
public class LambdaLogMetricScrapeTask extends TimerTask {
    @EqualsAndHashCode.Include
    private final String region;
    @Autowired
    private AWSClientProvider awsClientProvider;
    @Autowired
    private LambdaFunctionScraper lambdaFunctionScraper;
    @Autowired
    private GaugeExporter gaugeExporter;
    private final List<LogScrapeConfig> logScrapeConfigs;

    public LambdaLogMetricScrapeTask(String region, List<LogScrapeConfig> logScrapeConfigs) {
        this.region = region;
        this.logScrapeConfigs = logScrapeConfigs;
    }

    public void run() {
        Instant endTime = now().minusSeconds(60);
        Instant startTime = endTime.minusSeconds(60);

        log.info("BEGIN lambda log scrape for region {}", region);
        try {
            if (!lambdaFunctionScraper.getFunctions().containsKey(region)) {
                log.error("No functions found for region {}", region);
                return;
            }
            CloudWatchLogsClient cloudWatchLogsClient = awsClientProvider.getCloudWatchLogsClient(region);
            lambdaFunctionScraper.getFunctions().get(region).forEach((arn, functionConfig) -> logScrapeConfigs.stream()
                    .filter(config -> config.getLambdaFunctionName().equals(functionConfig.getName()))
                    .findFirst()
                    .ifPresent(logScrapeConfig -> {
                        String nextToken = null;
                        do {
                            FilterLogEventsRequest logEventsRequest = FilterLogEventsRequest.builder()
                                    .limit(10)
                                    .startTime(startTime.toEpochMilli())
                                    .endTime(endTime.toEpochMilli())
                                    .filterPattern(logScrapeConfig.getLogFilterPattern())
                                    .logGroupName(format("/aws/lambda/%s", functionConfig.getName()))
                                    .nextToken(nextToken)
                                    .build();

                            FilterLogEventsResponse response = cloudWatchLogsClient.filterLogEvents(logEventsRequest);
                            if (response.hasEvents()) {
                                Set<Map<String, String>> uniqueLabels = new LinkedHashSet<>();
                                log.info("log scrape config {} matched {} events", logScrapeConfig, response.events().size());
                                response.events().forEach(filteredLogEvent -> {
                                    Map<String, String> logLabels = logScrapeConfig.extractLabels(filteredLogEvent.message());
                                    if (logLabels.size() > 0) {
                                        logLabels.put("region", region);
                                        logLabels.put("d_function_name", functionConfig.getName());
                                        uniqueLabels.add(logLabels);
                                    }
                                });

                                uniqueLabels.forEach(labels ->
                                        gaugeExporter.exportMetric("aws_lambda_logs", "", labels, endTime,
                                                1.0D));
                            }
                            nextToken = response.nextToken();
                        } while (nextToken != null);
                    }));
        } catch (Exception e) {
            log.error("Failed to scrape lambda logs", e);
        }
        log.info("END lambda log scrape for region {}", region);
    }

    @VisibleForTesting
    Instant now() {
        return Instant.now();
    }
}
