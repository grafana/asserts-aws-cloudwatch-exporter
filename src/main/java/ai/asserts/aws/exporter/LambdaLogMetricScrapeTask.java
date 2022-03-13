package ai.asserts.aws.exporter;

import ai.asserts.aws.AWSClientProvider;
import ai.asserts.aws.cloudwatch.config.LogScrapeConfig;
import ai.asserts.aws.cloudwatch.config.ScrapeConfig;
import ai.asserts.aws.cloudwatch.config.ScrapeConfigProvider;
import ai.asserts.aws.lambda.LambdaFunction;
import ai.asserts.aws.lambda.LambdaFunctionScraper;
import ai.asserts.aws.lambda.LogEventMetricEmitter;
import ai.asserts.aws.lambda.LogEventScraper;
import io.prometheus.client.Collector;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
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
@EqualsAndHashCode(callSuper = false, onlyExplicitlyIncluded = true)
@Setter
public class LambdaLogMetricScrapeTask extends Collector implements MetricProvider {
    @EqualsAndHashCode.Include
    private final String region;
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

    private volatile Map<FunctionLogScrapeConfig, FilteredLogEvent> cache;

    public LambdaLogMetricScrapeTask(String region) {
        this.region = region;
        cache = new HashMap<>();
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
        scrapeConfig.getLambdaConfig().ifPresent(nc -> {
            log.info("BEGIN lambda log scrape for region {}", region);
            if (!CollectionUtils.isEmpty(nc.getLogs()) && lambdaFunctionScraper.getFunctions().containsKey(region)) {
                try (CloudWatchLogsClient cloudWatchLogsClient = awsClientProvider.getCloudWatchLogsClient(region,
                        scrapeConfig.getAssumeRole())) {
                    lambdaFunctionScraper.getFunctions().get(region).forEach((arn, functionConfig) -> nc.getLogs()
                            .stream()
                            .filter(logScrapeConfig -> logScrapeConfig.shouldScrapeLogsFor(functionConfig.getName()))
                            .findFirst()
                            .ifPresent(logScrapeConfig -> {
                                sleep(scrapeConfig.getLogScrapeDelaySeconds() * 1000);
                                Optional<FilteredLogEvent> logEventOpt = logEventScraper.findLogEvent(
                                        cloudWatchLogsClient, functionConfig, logScrapeConfig);
                                logEventOpt.ifPresent(logEvent ->
                                        map.put(new FunctionLogScrapeConfig(functionConfig, logScrapeConfig), logEvent));
                            }));
                } catch (Exception e) {
                    log.error("Failed to scrape lambda logs", e);
                }
            } else {
                log.info("No functions found for region {}", region);
            }
            log.info("END lambda log scrape for region {}", region);
        });
        return map;
    }

    private void sleep(long time) {
        try {
            Thread.sleep(time);
        } catch (InterruptedException e) {
            log.error("Interrupted", e);
        }
    }

    @Builder
    @EqualsAndHashCode
    @Getter
    public static class FunctionLogScrapeConfig {
        private final LambdaFunction lambdaFunction;
        private final LogScrapeConfig logScrapeConfig;
    }
}
