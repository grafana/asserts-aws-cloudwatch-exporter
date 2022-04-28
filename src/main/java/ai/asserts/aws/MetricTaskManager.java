package ai.asserts.aws;

import ai.asserts.aws.cloudwatch.alarms.AlarmMetricExporter;
import ai.asserts.aws.config.MetricConfig;
import ai.asserts.aws.config.ScrapeConfig;
import ai.asserts.aws.exporter.ECSServiceDiscoveryExporter;
import ai.asserts.aws.exporter.MetricScrapeTask;
import com.google.common.annotations.VisibleForTesting;
import io.micrometer.core.annotation.Timed;
import io.prometheus.client.CollectorRegistry;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;

@Component
@Slf4j
@AllArgsConstructor
public class MetricTaskManager implements InitializingBean {
    private final AccountProvider accountProvider;
    private final ScrapeConfigProvider scrapeConfigProvider;
    private final CollectorRegistry collectorRegistry;
    private final AutowireCapableBeanFactory beanFactory;
    private final ECSServiceDiscoveryExporter ecsServiceDiscoveryExporter;
    private final TaskThreadPool taskThreadPool;
    private final AlarmMetricExporter alarmMetricExporter;

    /**
     * Maintains the last scrape time for all the metrics of a given scrape interval. The scrapes are
     * not expected to happen concurrently so no need to worry about thread safety
     */
    @Getter
    private final Map<String, Map<String, Map<Integer, MetricScrapeTask>>> metricScrapeTasks = new TreeMap<>();

    public void afterPropertiesSet() {
        alarmMetricExporter.register(collectorRegistry);
    }

    @SuppressWarnings("unused")
    @Scheduled(fixedRateString = "${aws.metric.scrape.manager.task.fixedDelay:60000}",
            initialDelayString = "${aws.metric.scrape.manager.task.initialDelay:5000}")
    @Timed(description = "Time spent scraping cloudwatch metrics from all regions", histogram = true)
    public void triggerScrapes() {
        updateScrapeTasks();
        ExecutorService executorService = taskThreadPool.getExecutorService();
        metricScrapeTasks.values().stream()
                .flatMap(map -> map.values().stream())
                .flatMap(map -> map.values().stream())
                .forEach(task -> executorService.submit(task::update));

        executorService.submit(ecsServiceDiscoveryExporter);
    }

    @VisibleForTesting
    MetricScrapeTask newScrapeTask(String account, String assumeRole, String region, Integer interval, Integer delay) {
        return new MetricScrapeTask(account, assumeRole, region, interval, delay);
    }

    @VisibleForTesting
    void updateScrapeTasks() {
        ScrapeConfig scrapeConfig = scrapeConfigProvider.getScrapeConfig();
        Set<AccountProvider.AWSAccount> allAccounts = accountProvider.getAccounts();
        allAccounts.forEach(awsAccount -> awsAccount.getRegions().forEach(region -> scrapeConfig.getNamespaces().stream()
                .filter(nc -> !CollectionUtils.isEmpty(nc.getMetrics()))
                .flatMap(nc -> nc.getMetrics().stream().map(MetricConfig::getEffectiveScrapeInterval))
                .forEach(interval -> {
                    String accountId = awsAccount.getAccountId();
                    metricScrapeTasks.computeIfAbsent(accountId, k -> new TreeMap<>())
                            .computeIfAbsent(region, k -> new TreeMap<>())
                            .computeIfAbsent(interval, k -> metricScrapeTask(
                                    accountId, awsAccount.getAssumeRole(), region,
                                    interval, scrapeConfig.getDelay()));
                })));

        // Remove any accounts or regions that don't have to be scraped anymore
        Set<String> accounts = allAccounts.stream()
                .map(AccountProvider.AWSAccount::getAccountId)
                .collect(Collectors.toSet());
        metricScrapeTasks.entrySet().removeIf(entry -> !accounts.contains(entry.getKey()));
        allAccounts.forEach(account -> {
            Map<String, Map<Integer, MetricScrapeTask>> byRegions = metricScrapeTasks.get(account.getAccountId());
            byRegions.entrySet().removeIf(region -> !account.getRegions().contains(region.getKey()));
        });
    }

    private MetricScrapeTask metricScrapeTask(String account, String assumeRole, String region, Integer interval,
                                              Integer delay) {
        MetricScrapeTask metricScrapeTask = newScrapeTask(account, assumeRole, region, interval, delay);
        beanFactory.autowireBean(metricScrapeTask);
        metricScrapeTask.register(collectorRegistry);
        log.info("Setup metric scrape task for region {} and interval {}", region, interval);
        return metricScrapeTask;
    }
}
