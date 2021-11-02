/*
 * Copyright © 2021
 * Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws;

import ai.asserts.aws.cloudwatch.config.MetricConfig;
import ai.asserts.aws.cloudwatch.config.ScrapeConfig;
import ai.asserts.aws.cloudwatch.config.ScrapeConfigProvider;
import ai.asserts.aws.cloudwatch.metrics.MetricScrapeTask;
import ai.asserts.aws.lambda.LambdaCapacityExporter;
import ai.asserts.aws.lambda.LambdaEventSourceExporter;
import ai.asserts.aws.lambda.LambdaLogMetricScrapeTask;
import com.google.common.annotations.VisibleForTesting;
import io.micrometer.core.annotation.Timed;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TimerTask;
import java.util.TreeMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

@Component
@Slf4j
public class ScrapeTaskManager {
    private final AutowireCapableBeanFactory beanFactory;
    private final ScrapeConfigProvider scrapeConfigProvider;
    private final LambdaEventSourceExporter lambdaEventSourceExporter;
    /**
     * Maintains the last scrape time for all the metricso of a given scrape interval. The scrapes are
     * not expected to happen concurrently so no need to worry about thread safety
     */
    private final Map<Integer, Map<String, MetricScrapeTask>> metricScrapeTasks = new TreeMap<>();
    private final Map<Integer, Map<String, Set<TimerTask>>> logScrapeTasks = new TreeMap<>();
    private final ScheduledExecutorService scheduledThreadPoolExecutor;

    public ScrapeTaskManager(AutowireCapableBeanFactory beanFactory, ScrapeConfigProvider scrapeConfigProvider,
                             LambdaEventSourceExporter lambdaEventSourceExporter) {
        this.beanFactory = beanFactory;
        this.scrapeConfigProvider = scrapeConfigProvider;
        this.lambdaEventSourceExporter = lambdaEventSourceExporter;
        ScrapeConfig scrapeConfig = scrapeConfigProvider.getScrapeConfig();
        this.scheduledThreadPoolExecutor = getExecutorService(scrapeConfig.getNumTaskThreads());
    }

    @SuppressWarnings("unused")
    @Scheduled(fixedDelayString = "${aws.metric.scrape.manager.task.fixedDelay:900000}",
            initialDelayString = "${aws.metric.scrape.manager.task.initialDelay:5000}")
    @Timed(description = "Time spent scraping cloudwatch metrics from all regions", histogram = true)
    public void setupScrapeTasks() {
        ScrapeConfig scrapeConfig = scrapeConfigProvider.getScrapeConfig();
        scrapeConfig.getNamespaces().forEach(nc -> nc.getMetrics().stream()
                .map(MetricConfig::getScrapeInterval)
                .forEach(interval -> scrapeConfig.getRegions().forEach(region -> {
                            Map<String, MetricScrapeTask> byRegion = metricScrapeTasks.computeIfAbsent(interval,
                                    k -> new TreeMap<>());
                            if (!byRegion.containsKey(region)) {
                                byRegion.put(region,
                                        metricScrapeTask(region, interval, scrapeConfig.getDelay()));
                            }
                        }
                )));

        scrapeConfig.getLambdaConfig().ifPresent(nc -> {
            if (!CollectionUtils.isEmpty(nc.getLogs())) {
                scrapeConfig.getRegions().forEach(region -> logScrapeTasks
                        .computeIfAbsent(60, k -> new TreeMap<>())
                        .computeIfAbsent(region, k -> new HashSet<>())
                        .add(lambdaLogScrapeTask(nc, region))
                );
            }
        });

        setupMetadataTasks();
    }

    private void setupMetadataTasks() {
        Instant instant = scheduleTask(60, lambdaEventSourceExporter);
        log.info("Scheduled Lambda Event Source Exporter task at interval {} from {}", 60, instant);

        LambdaCapacityExporter lambdaCapacityExporter = new LambdaCapacityExporter();
        beanFactory.autowireBean(lambdaCapacityExporter);
        instant = scheduleTask(60, lambdaCapacityExporter);
        log.info("Scheduled Lambda Capacity Exporter task at interval {} from {}", 60, instant);
    }

    @VisibleForTesting
    ScheduledExecutorService getExecutorService(int numThreads) {
        return Executors.newScheduledThreadPool(numThreads);
    }

    @VisibleForTesting
    MetricScrapeTask newScrapeTask(String region, Integer interval, Integer delay) {
        return new MetricScrapeTask(region, interval, delay);
    }

    private LambdaLogMetricScrapeTask lambdaLogScrapeTask(ai.asserts.aws.cloudwatch.config.NamespaceConfig nc,
                                                          String region) {
        log.info("Setup lambda log scrape task for region {} with scrape configs {}", region, nc.getLogs());
        LambdaLogMetricScrapeTask logScraperTask = new LambdaLogMetricScrapeTask(region, nc.getLogs());
        beanFactory.autowireBean(logScraperTask);
        Instant instant = scheduleTask(60, logScraperTask);
        log.info("Setup log scrape task for region {} and interval {} from {}", region, 60, instant);
        return logScraperTask;
    }

    private MetricScrapeTask metricScrapeTask(String region, Integer interval, Integer delay) {
        MetricScrapeTask metricScrapeTask = newScrapeTask(region, interval, delay);
        beanFactory.autowireBean(metricScrapeTask);
        metricScrapeTask.register();
        log.info("Setup metric scrape task for region {} and interval {}", region, interval);
        return metricScrapeTask;
    }

    private Instant scheduleTask(Integer interval, TimerTask timerTask) {
        long epochMilli = Instant.now().toEpochMilli();
        int intervalMillis = interval * 1000;
        long delay = 60_000L - epochMilli % 60_000L;
        scheduledThreadPoolExecutor.scheduleAtFixedRate(timerTask, delay, intervalMillis, MILLISECONDS);
        return Instant.ofEpochMilli(epochMilli + delay);
    }
}
