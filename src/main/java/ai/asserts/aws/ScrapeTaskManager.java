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
import io.prometheus.client.CollectorRegistry;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
@Slf4j
@AllArgsConstructor
public class ScrapeTaskManager implements InitializingBean {
    private final CollectorRegistry collectorRegistry;
    private final AutowireCapableBeanFactory beanFactory;
    private final ScrapeConfigProvider scrapeConfigProvider;
    private final LambdaCapacityExporter lambdaCapacityExporter;
    private final LambdaEventSourceExporter lambdaEventSourceExporter;
    /**
     * Maintains the last scrape time for all the metricso of a given scrape interval. The scrapes are
     * not expected to happen concurrently so no need to worry about thread safety
     */
    private final Map<Integer, Map<String, MetricScrapeTask>> metricScrapeTasks = new TreeMap<>();
    private final Map<Integer, Map<String, Set<LambdaLogMetricScrapeTask>>> logScrapeTasks = new TreeMap<>();
    private final AtomicBoolean tasksSetup = new AtomicBoolean(false);


    public void afterPropertiesSet() {
        if (!tasksSetup.get()) {
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
            tasksSetup.set(true);
        }
    }

    private void setupMetadataTasks() {
        lambdaCapacityExporter.register(collectorRegistry);
        lambdaEventSourceExporter.register(collectorRegistry);
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
