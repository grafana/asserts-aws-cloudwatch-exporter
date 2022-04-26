
package ai.asserts.aws.exporter;

import ai.asserts.aws.AWSClientProvider;
import ai.asserts.aws.AccountProvider;
import ai.asserts.aws.AccountProvider.AWSAccount;
import ai.asserts.aws.ScrapeConfigProvider;
import ai.asserts.aws.config.LogScrapeConfig;
import ai.asserts.aws.config.ScrapeConfig;
import ai.asserts.aws.lambda.LambdaFunction;
import ai.asserts.aws.lambda.LambdaFunctionScraper;
import ai.asserts.aws.lambda.LogEventMetricEmitter;
import ai.asserts.aws.lambda.LogEventScraper;
import io.prometheus.client.Collector;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;
import software.amazon.awssdk.services.cloudwatchlogs.model.FilteredLogEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static io.prometheus.client.Collector.Type.GAUGE;

/**
 * Scrapes the cloudwatch logs and converts log messages into <code>aws_lambda_logs</code> metrics based
 * on the provided {@link LogScrapeConfig}s
 */
@Slf4j
@Component
public class LambdaLogMetricScrapeTask extends Collector implements MetricProvider {
    private final AccountProvider accountProvider;
    private final AWSClientProvider awsClientProvider;
    private final ScrapeConfigProvider scrapeConfigProvider;
    private final LambdaFunctionScraper lambdaFunctionScraper;
    private final LogEventScraper logEventScraper;
    private final LogEventMetricEmitter logEventMetricEmitter;

    private volatile Map<FunctionLogScrapeConfig, FilteredLogEvent> cache;

    public LambdaLogMetricScrapeTask(AccountProvider accountProvider, AWSClientProvider awsClientProvider,
                                     ScrapeConfigProvider scrapeConfigProvider,
                                     LambdaFunctionScraper lambdaFunctionScraper, LogEventScraper logEventScraper,
                                     LogEventMetricEmitter logEventMetricEmitter) {
        this.accountProvider = accountProvider;
        this.awsClientProvider = awsClientProvider;
        this.scrapeConfigProvider = scrapeConfigProvider;
        this.lambdaFunctionScraper = lambdaFunctionScraper;
        this.logEventScraper = logEventScraper;
        this.logEventMetricEmitter = logEventMetricEmitter;
        this.cache = new HashMap<>();
    }

    public List<MetricFamilySamples> collect() {
        List<Collector.MetricFamilySamples.Sample> samples = new ArrayList<>();
        ScrapeConfig scrapeConfig = scrapeConfigProvider.getScrapeConfig();
        scrapeConfig.getLambdaConfig().ifPresent(namespaceConfig -> {
            Map<FunctionLogScrapeConfig, FilteredLogEvent> copy = this.cache;
            copy.forEach((config, event) ->
                    logEventMetricEmitter.getSample(namespaceConfig, config, event)
                            .ifPresent(samples::add));
        });
        return Collections.singletonList(new MetricFamilySamples("aws_lambda_logs", GAUGE, "", samples));
    }

    @Override
    public void update() {
        this.cache = scrapeLogEvents();
    }

    private Map<FunctionLogScrapeConfig, FilteredLogEvent> scrapeLogEvents() {
        Map<FunctionLogScrapeConfig, FilteredLogEvent> map = new HashMap<>();

        ScrapeConfig scrapeConfig = scrapeConfigProvider.getScrapeConfig();
        Map<String, Map<String, Map<String, LambdaFunction>>> byAccountByRegion = lambdaFunctionScraper.getFunctions();
        scrapeConfig.getLambdaConfig().ifPresent(nc -> {
            for (AWSAccount accountRegion : accountProvider.getAccounts()) {
                String account = accountRegion.getAccountId();
                if (!CollectionUtils.isEmpty(nc.getLogs()) &&
                        byAccountByRegion.containsKey(account)) {
                    log.info("BEGIN lambda log scrape for account {}", account);
                    Map<String, Map<String, LambdaFunction>> byRegion = byAccountByRegion.get(account);
                    accountRegion.getRegions().forEach(region -> scrapeLogs(accountRegion, map, scrapeConfig, nc,
                            byRegion, region));
                } else {
                    log.info("No functions found for account {}", account);
                }
                log.info("END lambda log scrape for account {}", account);
            }

        });
        return map;
    }

    private void scrapeLogs(
            AWSAccount account,
            Map<FunctionLogScrapeConfig, FilteredLogEvent> map, ScrapeConfig scrapeConfig,
            ai.asserts.aws.config.NamespaceConfig nc,
            Map<String, Map<String, LambdaFunction>> byRegion, String region) {
        String assumeRole = account.getAssumeRole();
        try (CloudWatchLogsClient cloudWatchLogsClient = awsClientProvider.getCloudWatchLogsClient(region, assumeRole)) {
            byRegion.get(region).forEach((arn, functionConfig) -> nc.getLogs()
                    .stream()
                    .filter(logScrapeConfig -> logScrapeConfig.shouldScrapeLogsFor(functionConfig.getName()))
                    .findFirst()
                    .ifPresent(logScrapeConfig -> {
                        sleep(scrapeConfig.getLogScrapeDelaySeconds() * 1000);
                        Optional<FilteredLogEvent> logEventOpt = logEventScraper.findLogEvent(
                                cloudWatchLogsClient, functionConfig, logScrapeConfig);
                        logEventOpt.ifPresent(logEvent ->
                                map.put(new FunctionLogScrapeConfig(account, functionConfig, logScrapeConfig), logEvent));
                    }));
        } catch (Exception e) {
            log.error("Failed to scrape lambda logs", e);
        }
    }

    @Builder
    @EqualsAndHashCode
    @Getter
    @ToString
    public static class FunctionLogScrapeConfig {
        private final AWSAccount account;
        private final LambdaFunction lambdaFunction;
        private final LogScrapeConfig logScrapeConfig;
    }

    private void sleep(long time) {
        try {
            Thread.sleep(time);
        } catch (InterruptedException e) {
            log.error("Interrupted", e);
        }
    }
}
