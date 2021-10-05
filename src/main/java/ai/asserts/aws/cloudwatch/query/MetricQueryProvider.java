/*
 * Copyright © 2021
 * Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.cloudwatch.query;

import ai.asserts.aws.AWSClientProvider;
import ai.asserts.aws.MetricNameUtil;
import ai.asserts.aws.cloudwatch.config.MetricConfig;
import ai.asserts.aws.cloudwatch.config.ScrapeConfig;
import ai.asserts.aws.cloudwatch.config.ScrapeConfigProvider;
import com.google.common.base.Suppliers;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.ListMetricsRequest;
import software.amazon.awssdk.services.cloudwatch.model.ListMetricsResponse;
import software.amazon.awssdk.services.cloudwatch.model.Metric;
import software.amazon.awssdk.services.cloudwatch.model.MetricDataQuery;
import software.amazon.awssdk.services.cloudwatch.model.MetricStat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

@Component
@Slf4j
public class MetricQueryProvider {
    private final ScrapeConfigProvider scrapeConfigProvider;
    private final QueryIdGenerator queryIdGenerator;
    private final MetricNameUtil metricNameUtil;
    private final AWSClientProvider awsClientProvider;
    private final Supplier<Map<String, Map<Integer, List<MetricQuery>>>> metricQueryCache;

    public MetricQueryProvider(ScrapeConfigProvider scrapeConfigProvider,
                               QueryIdGenerator queryIdGenerator,
                               MetricNameUtil metricNameUtil, AWSClientProvider awsClientProvider) {
        this.scrapeConfigProvider = scrapeConfigProvider;
        this.queryIdGenerator = queryIdGenerator;
        this.metricNameUtil = metricNameUtil;
        this.awsClientProvider = awsClientProvider;
        metricQueryCache = Suppliers.memoizeWithExpiration(this::getQueriesInternal, 10, TimeUnit.MINUTES);
        log.info("Initialized..");
    }

    public Map<String, Map<Integer, List<MetricQuery>>> getMetricQueries() {
        return metricQueryCache.get();
    }

    Map<String, Map<Integer, List<MetricQuery>>> getQueriesInternal() {
        log.info("Will discover metrics and build metric queries");

        Map<String, Map<Integer, List<MetricQuery>>> byIntervalWithDimensions = new TreeMap<>();

        ScrapeConfig scrapeConfig = scrapeConfigProvider.getScrapeConfig();
        scrapeConfig.getRegions().forEach(region -> scrapeConfig.getNamespaces().forEach(ns -> {
            try {
                CloudWatchClient cloudWatchClient = awsClientProvider.getCloudWatchClient(region);

                Map<String, MetricConfig> configuredMetrics = new TreeMap<>();
                ns.getMetrics().forEach(metricConfig -> configuredMetrics.put(metricConfig.getName(), metricConfig));

                String nextToken = null;
                do {
                    ListMetricsRequest.Builder builder = ListMetricsRequest.builder()
                            .nextToken(nextToken)
                            .namespace(ns.getName());

                    log.info("Discovering all metrics for region={}, namespace={} ", region, ns.getName());

                    ListMetricsResponse response = cloudWatchClient.listMetrics(builder.build());
                    if (response.hasMetrics()) {
                        response.metrics().forEach(metric -> {
                            if (configuredMetrics.containsKey(metric.metricName())) {
                                MetricConfig metricConfig = configuredMetrics.get(metric.metricName());
                                if (metricConfig.matchesMetric(metric)) {
                                    metricConfig.getStats().forEach(stat -> {
                                        byIntervalWithDimensions
                                                .computeIfAbsent(region, k -> new TreeMap<>())
                                                .computeIfAbsent(metricConfig.getScrapeInterval(), k -> new ArrayList<>())
                                                .add(buildQuery(metricConfig, stat, metric));
                                        log.info("Will scrape metric {} agg over {} seconds every {} seconds",
                                                metricNameUtil.exportedMetric(metric, stat),
                                                metricConfig.getPeriod(), metricConfig.getScrapeInterval());
                                    });
                                }
                            }
                        });
                    }
                    nextToken = response.nextToken();
                } while (nextToken != null);
            } catch (Exception e) {
                log.info("Failed to scrape metrics", e);
            }
        }));

        return byIntervalWithDimensions;
    }

    private MetricQuery buildQuery(MetricConfig metricConfig,
                                   ai.asserts.aws.cloudwatch.model.MetricStat stat,
                                   Metric metric) {
        MetricStat metricStat = MetricStat.builder()
                .period(metricConfig.getPeriod())
                .stat(stat.toString())
                .metric(metric)
                .build();
        return MetricQuery.builder()
                .metricConfig(metricConfig)
                .metric(metric)
                .expectedSamples(metricConfig.numSamplesPerScrape())
                .metricStat(stat)
                .metricDataQuery(MetricDataQuery.builder()
                        .id(queryIdGenerator.next())
                        .metricStat(metricStat)
                        .build())
                .build();
    }
}
