/*
 * Copyright © 2021
 * Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.lambda;

import ai.asserts.aws.AWSClientProvider;
import ai.asserts.aws.cloudwatch.config.LogScrapeConfig;
import ai.asserts.aws.cloudwatch.config.ScrapeConfig;
import ai.asserts.aws.cloudwatch.config.ScrapeConfigProvider;
import com.google.common.base.Suppliers;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;
import software.amazon.awssdk.services.cloudwatchlogs.model.FilteredLogEvent;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TimerTask;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

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
    @EqualsAndHashCode.Include
    private final List<LogScrapeConfig> logScrapeConfigs;
    @Autowired
    private AWSClientProvider awsClientProvider;
    @Autowired
    private ScrapeConfigProvider scrapeConfigProvider;
    @Autowired
    private LambdaFunctionScraper lambdaFunctionScraper;
    @Autowired
    private LogEventScraper logEventScraper;
    @Autowired
    private LogEventMetricEmitter logEventMetricEmitter;

    private Supplier<Map<FunctionLogScrapeConfig, FilteredLogEvent>> cache;

    public LambdaLogMetricScrapeTask(String region, List<LogScrapeConfig> logScrapeConfigs) {
        this.region = region;
        this.logScrapeConfigs = logScrapeConfigs;
        cache = Suppliers.memoizeWithExpiration(this::scrapeLogEvents, 15, TimeUnit.MINUTES);
    }

    public void run() {
        ScrapeConfig scrapeConfig = scrapeConfigProvider.getScrapeConfig();
        scrapeConfig.getLambdaConfig()
                .ifPresent(namespaceConfig ->
                        cache.get().forEach((config, event) ->
                                logEventMetricEmitter.emitMetric(namespaceConfig, config, event)));
    }

    private Map<FunctionLogScrapeConfig, FilteredLogEvent> scrapeLogEvents() {
        log.info("BEGIN lambda log scrape for region {}", region);
        Map<FunctionLogScrapeConfig, FilteredLogEvent> map = new HashMap<>();
        try {

            if (!lambdaFunctionScraper.getFunctions().containsKey(region)) {
                log.info("No functions found for region {}", region);
                return Collections.emptyMap();
            }
            CloudWatchLogsClient cloudWatchLogsClient = awsClientProvider.getCloudWatchLogsClient(region);
            lambdaFunctionScraper.getFunctions().get(region).forEach((arn, functionConfig) -> logScrapeConfigs.stream()
                    .filter(logScrapeConfig -> logScrapeConfig.shouldScrapeLogsFor(functionConfig.getName()))
                    .findFirst()
                    .ifPresent(logScrapeConfig -> {
                        Optional<FilteredLogEvent> logEventOpt = logEventScraper.findLogEvent(cloudWatchLogsClient,
                                functionConfig, logScrapeConfig);
                        logEventOpt.ifPresent(logEvent ->
                                map.put(new FunctionLogScrapeConfig(functionConfig, logScrapeConfig), logEvent));
                    }));
        } catch (Exception e) {
            log.error("Failed to scrape lambda logs", e);
        }
        log.info("END lambda log scrape for region {}", region);
        return map;
    }

    @Builder
    @EqualsAndHashCode
    @Getter
    public static class FunctionLogScrapeConfig {
        private final LambdaFunction lambdaFunction;
        private final LogScrapeConfig logScrapeConfig;
    }
}
