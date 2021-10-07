/*
 * Copyright © 2021
 * Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws;

import ai.asserts.aws.cloudwatch.config.MetricConfig;
import ai.asserts.aws.cloudwatch.config.ScrapeConfig;
import ai.asserts.aws.cloudwatch.config.ScrapeConfigProvider;
import ai.asserts.aws.cloudwatch.metrics.MetricScrapeTask;
import ai.asserts.aws.cloudwatch.model.CWNamespace;
import ai.asserts.aws.lambda.LambdaEventSourceExporter;
import ai.asserts.aws.lambda.LambdaLogMetricScrapeTask;
import com.google.common.annotations.VisibleForTesting;
import io.micrometer.core.annotation.Timed;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.TreeMap;

@Component
@Slf4j
@AllArgsConstructor
public class ScrapeTaskManager {
    private final AutowireCapableBeanFactory beanFactory;
    private final ScrapeConfigProvider scrapeConfigProvider;
    private final LambdaEventSourceExporter lambdaEventSourceExporter;
    /**
     * Maintains the last scrape time for all the metricso of a given scrape interval. The scrapes are
     * not expected to happen concurrently so no need to worry about thread safety
     */
    private final Map<Integer, Map<String, TimerTask>> metricScrapeTasks = new TreeMap<>();
    private final Map<Integer, Map<String, Set<TimerTask>>> logScrapeTasks = new TreeMap<>();
    private final Timer timer = getTimer();


    @SuppressWarnings("unused")
    @Scheduled(fixedDelayString = "${aws.metric.scrape.manager.task.fixedDelay:900000}",
            initialDelayString = "${aws.metric.scrape.manager.task.initialDelay:5000}")
    @Timed(description = "Time spent scraping cloudwatch metrics from all regions", histogram = true)
    public void setupScrapeTasks() {
        ScrapeConfig scrapeConfig = scrapeConfigProvider.getScrapeConfig();
        Map<String, TimerTask> taskMap = metricScrapeTasks.computeIfAbsent(60, k -> new TreeMap<>());
        if (!taskMap.containsKey(lambdaEventSourceExporter.getClass().getName())) {
            scheduleTask(60, lambdaEventSourceExporter);
            taskMap.put(lambdaEventSourceExporter.getClass().getName(), lambdaEventSourceExporter);
        }
        log.info("Setup Lambda function scraper");

        scrapeConfig.getNamespaces()
                .forEach(nc -> {
                    nc.getMetrics().stream()
                            .map(MetricConfig::getScrapeInterval)
                            .forEach(interval -> scrapeConfig.getRegions().forEach(region -> {
                                        Map<String, TimerTask> byRegion = metricScrapeTasks.computeIfAbsent(interval,
                                                k -> new TreeMap<>());
                                        if (!byRegion.containsKey(region)) {
                                            byRegion.put(region, metricScrapeTask(region, interval));
                                        }
                                    }
                            ));

                    if (CWNamespace.lambda.getServiceName().equals(nc.getName()) &&
                            !CollectionUtils.isEmpty(nc.getLogs())) {
                        scrapeConfig.getRegions().forEach(region -> logScrapeTasks
                                .computeIfAbsent(60, k -> new TreeMap<>())
                                .computeIfAbsent(region, k -> new HashSet<>())
                                .add(lambdaLogScrapeTask(nc, region))
                        );
                    }
                });
    }

    @VisibleForTesting
    Timer getTimer() {
        return new Timer();
    }

    private LambdaLogMetricScrapeTask lambdaLogScrapeTask(ai.asserts.aws.cloudwatch.config.NamespaceConfig nc, String region) {
        log.info("Setup lambda log scrape task for region {} with scrape configs {}", region, nc.getLogs());
        LambdaLogMetricScrapeTask logScraperTask = new LambdaLogMetricScrapeTask(region, nc.getLogs());
        beanFactory.autowireBean(logScraperTask);
        scheduleTask(60, logScraperTask);
        return logScraperTask;
    }

    private MetricScrapeTask metricScrapeTask(String region, Integer interval) {
        MetricScrapeTask metricScrapeTask = new MetricScrapeTask(region, interval);
        beanFactory.autowireBean(metricScrapeTask);
        scheduleTask(interval, metricScrapeTask);
        log.info("Setup metric scrape task for region {} and interval {}", region, interval);
        return metricScrapeTask;
    }

    private void scheduleTask(Integer interval, TimerTask timerTask) {
        long epochMilli = Instant.now().toEpochMilli();
        int intervalMillis = interval * 1000;
        long delay = intervalMillis - epochMilli % intervalMillis;
        timer.schedule(timerTask, delay, intervalMillis);
    }
}
