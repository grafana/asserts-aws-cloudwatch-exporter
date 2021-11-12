/*
 * Copyright © 2021
 * Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws;

import ai.asserts.aws.cloudwatch.config.MetricConfig;
import ai.asserts.aws.cloudwatch.config.ScrapeConfig;
import ai.asserts.aws.cloudwatch.config.ScrapeConfigProvider;
import ai.asserts.aws.exporter.BasicMetricCollector;
import ai.asserts.aws.exporter.LambdaInvokeConfigExporter;
import ai.asserts.aws.exporter.MetricScrapeTask;
import ai.asserts.aws.exporter.LambdaCapacityExporter;
import ai.asserts.aws.exporter.LambdaEventSourceExporter;
import ai.asserts.aws.exporter.LambdaLogMetricScrapeTask;
import ai.asserts.aws.exporter.ResourceTagExporter;
import com.google.common.annotations.VisibleForTesting;
import io.micrometer.core.annotation.Timed;
import io.prometheus.client.CollectorRegistry;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Component
@Slf4j
@AllArgsConstructor
public class ScrapeTaskManager implements InitializingBean {
    private final CollectorRegistry collectorRegistry;
    private final AutowireCapableBeanFactory beanFactory;
    private final ScrapeConfigProvider scrapeConfigProvider;
    private final LambdaCapacityExporter lambdaCapacityExporter;
    private final LambdaEventSourceExporter lambdaEventSourceExporter;
    private final LambdaInvokeConfigExporter lambdaInvokeConfigExporter;
    private final BasicMetricCollector metricCollector;
    private final ResourceTagExporter resourceTagExporter;

    /**
     * Maintains the last scrape time for all the metricso of a given scrape interval. The scrapes are
     * not expected to happen concurrently so no need to worry about thread safety
     */
    private final Map<Integer, Map<String, MetricScrapeTask>> metricScrapeTasks = new TreeMap<>();
    private final Map<Integer, Map<String, Set<LambdaLogMetricScrapeTask>>> logScrapeTasks = new TreeMap<>();
    private final ExecutorService executorService = Executors.newFixedThreadPool(10);

    public void afterPropertiesSet() {
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

    @SuppressWarnings("unused")
    @Scheduled(fixedDelayString = "${aws.metric.scrape.manager.task.fixedDelay:60000}",
            initialDelayString = "${aws.metric.scrape.manager.task.initialDelay:5000}")
    @Timed(description = "Time spent scraping cloudwatch metrics from all regions", histogram = true)
    public void triggerScrapes() {
        metricScrapeTasks.values().stream()
                .flatMap(map -> map.values().stream())
                .forEach(task -> executorService.submit(task::update));

        logScrapeTasks.values().stream()
                .flatMap(map -> map.values().stream())
                .flatMap(Collection::stream)
                .forEach(task -> executorService.submit(task::update));

        executorService.submit(lambdaCapacityExporter::update);
        executorService.submit(lambdaEventSourceExporter::update);
    }

    private void setupMetadataTasks() {
        lambdaCapacityExporter.register(collectorRegistry);
        lambdaEventSourceExporter.register(collectorRegistry);
        lambdaInvokeConfigExporter.register(collectorRegistry);
        resourceTagExporter.register(collectorRegistry);
        metricCollector.register(collectorRegistry);
    }

    @VisibleForTesting
    MetricScrapeTask newScrapeTask(String region, Integer interval, Integer delay) {
        return new MetricScrapeTask(region, interval, delay);
    }

    @VisibleForTesting
    LambdaLogMetricScrapeTask newLogScrapeTask(String region) {
        return new LambdaLogMetricScrapeTask(region);
    }

    private LambdaLogMetricScrapeTask lambdaLogScrapeTask(ai.asserts.aws.cloudwatch.config.NamespaceConfig nc,
                                                          String region) {
        log.info("Setup lambda log scrape task for region {} with scrape configs {}", region, nc.getLogs());
        LambdaLogMetricScrapeTask logScraperTask = newLogScrapeTask(region);
        beanFactory.autowireBean(logScraperTask);
        logScraperTask.register(collectorRegistry);
        return logScraperTask;
    }

    private MetricScrapeTask metricScrapeTask(String region, Integer interval, Integer delay) {
        MetricScrapeTask metricScrapeTask = newScrapeTask(region, interval, delay);
        beanFactory.autowireBean(metricScrapeTask);
        metricScrapeTask.register(collectorRegistry);
        log.info("Setup metric scrape task for region {} and interval {}", region, interval);
        return metricScrapeTask;
    }
}
