/*
 * Copyright © 2021
 * Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.cloudwatch.metrics;

import ai.asserts.aws.AWSClientProvider;
import ai.asserts.aws.cloudwatch.config.MetricConfig;
import ai.asserts.aws.cloudwatch.prometheus.GaugeExporter;
import ai.asserts.aws.cloudwatch.prometheus.MetricProvider;
import ai.asserts.aws.cloudwatch.query.MetricQuery;
import ai.asserts.aws.cloudwatch.query.MetricQueryProvider;
import ai.asserts.aws.cloudwatch.query.QueryBatcher;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableMap;
import io.prometheus.client.Collector;
import io.prometheus.client.CollectorRegistry;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.GetMetricDataRequest;
import software.amazon.awssdk.services.cloudwatch.model.GetMetricDataResponse;
import software.amazon.awssdk.services.cloudwatch.model.Metric;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static ai.asserts.aws.MetricNameUtil.SCRAPE_INTERVAL_LABEL;
import static ai.asserts.aws.MetricNameUtil.SCRAPE_LATENCY_METRIC;
import static ai.asserts.aws.MetricNameUtil.SCRAPE_OPERATION_LABEL;
import static ai.asserts.aws.MetricNameUtil.SCRAPE_REGION_LABEL;
import static java.util.concurrent.TimeUnit.SECONDS;
import static software.amazon.awssdk.services.cloudwatch.model.StatusCode.COMPLETE;

/**
 * Scrapes metrics using the <code>GetMetricData</code> AWS API. Depends on {@link MetricQueryProvider} to provide
 * the queries for the region that it scrapes. The metrics are split into batches to meet the following constraints
 * <ol>
 *     <li>A maximum of 500 metrics per API call</li>
 *     <li>A maximum of 100800 data points returned in each call</li>
 * </ol>
 * <p>
 * The number of samples per metric is determined by {@link MetricConfig#numSamplesPerScrape()}
 */
@Slf4j
@Setter
@Getter
@EqualsAndHashCode(callSuper = false, onlyExplicitlyIncluded = true)
public class MetricScrapeTask extends Collector implements MetricProvider {
    @Autowired
    private AWSClientProvider awsClientProvider;
    @Autowired
    private MetricQueryProvider metricQueryProvider;
    @Autowired
    private QueryBatcher queryBatcher;
    @Autowired
    private GaugeExporter gaugeExporter;
    @Autowired
    private MetricSampleBuilder sampleBuilder;
    @Autowired
    private CollectorRegistry collectorRegistry;
    @Autowired
    private TimeWindowBuilder timeWindowBuilder;
    @EqualsAndHashCode.Include
    private final String region;
    @EqualsAndHashCode.Include
    private final int intervalSeconds;
    @EqualsAndHashCode.Include
    private final int delaySeconds;

    private Supplier<List<MetricFamilySamples>> cache;

    public MetricScrapeTask(String region, int intervalSeconds, int delay) {
        this.region = region;
        this.intervalSeconds = intervalSeconds;
        this.delaySeconds = delay;
        this.cache = Suppliers.memoizeWithExpiration(this::fetchMetricsFromCW, intervalSeconds, SECONDS);
    }

    @Override
    public List<MetricFamilySamples> collect() {
        return cache.get();
    }

    private List<MetricFamilySamples> fetchMetricsFromCW() {
        List<MetricFamilySamples> familySamples = new ArrayList<>();

        log.info("BEGIN Scrape for region {} and interval {}", region, intervalSeconds);
        Map<Integer, List<MetricQuery>> byInterval = metricQueryProvider.getMetricQueries().get(region);
        if (byInterval == null) {
            log.error("No queries found for region {}", region);
            return Collections.emptyList();
        }

        List<MetricQuery> queries = byInterval.get(intervalSeconds);
        if (queries == null) {
            log.error("No queries found for region {} and interval {}", region, intervalSeconds);
            return Collections.emptyList();
        }
        boolean s3DailyMetric = queries.stream()
                .anyMatch(metricQuery -> "AWS/S3".equals(metricQuery.getMetric().namespace()));

        // The result only has the query id. We will need the metric while processing the result
        // so build a map for lookup
        Map<String, MetricQuery> queriesById = mapQueriesById(queries);

        List<List<MetricQuery>> batches = queryBatcher.splitIntoBatches(queries);
        log.info("Split metric queries into {} batches", batches.size());

        Map<String, List<MetricFamilySamples.Sample>> samplesByMetric = new TreeMap<>();

        try (CloudWatchClient cloudWatchClient = awsClientProvider.getCloudWatchClient(region)) {
            batches.forEach(batch -> {
                String nextToken = null;
                // For now, S3 is the only one which has a different period of 1 day. All other metrics are 1m
                Instant[] timePeriod = s3DailyMetric ? timeWindowBuilder.getDailyMetricTimeWindow(region) :
                        timeWindowBuilder.getTimePeriod(region);
                log.info("Scraping metrics for time period {} - {}", timePeriod[0], timePeriod[1]);
                do {
                    GetMetricDataRequest.Builder requestBuilder = GetMetricDataRequest.builder()
                            .startTime(timePeriod[0].minusSeconds(delaySeconds))
                            .endTime(timePeriod[1].minusSeconds(delaySeconds))
                            .nextToken(nextToken)
                            .metricDataQueries(batch.stream()
                                    .map(MetricQuery::getMetricDataQuery)
                                    .collect(Collectors.toList()));

                    long timeTaken = System.currentTimeMillis();
                    GetMetricDataResponse metricData = cloudWatchClient.getMetricData(requestBuilder.build());
                    timeTaken = System.currentTimeMillis() - timeTaken;
                    captureLatency(timeTaken);

                    if (metricData.hasMetricDataResults()) {
                        metricData.metricDataResults()
                                .stream().filter(metricDataResult -> !metricDataResult.statusCode().equals(COMPLETE))
                                .forEach(metricDataResult -> {
                                    Metric metric = queriesById.get(metricDataResult.id()).getMetric();
                                    log.error("Metric not available for {}::{}::{}",
                                            metric.namespace(), metric.metricName(), metric.dimensions().stream()
                                                    .map(d -> String.format("%s=\"%s\"", d.name(), d.value()))
                                                    .collect(Collectors.joining(", ")));
                                });
                        metricData.metricDataResults()
                                .stream().filter(metricDataResult -> metricDataResult.statusCode().equals(COMPLETE))
                                .forEach(metricDataResult -> {
                                    MetricQuery metricQuery = queriesById.remove(metricDataResult.id());
                                    List<MetricFamilySamples.Sample> samples = sampleBuilder.buildSamples(region,
                                            metricQuery, metricDataResult);

                                    samples.forEach(sample ->
                                            samplesByMetric.computeIfAbsent(sample.name, k -> new ArrayList<>())
                                                    .add(sample));
                                });
                    }
                    nextToken = metricData.nextToken();
                } while (nextToken != null);
            });
        } catch (Exception e) {
            log.error("Failed to scrape metrics", e);
        }

        if (samplesByMetric.size() > 0) {
            log.info("Got samples for {}", samplesByMetric.keySet());
        } else {
            log.info("Didn't find any samples for region {} and interval {}", region, intervalSeconds);
        }
        samplesByMetric.forEach((metricName, samples) -> familySamples.add(sampleBuilder.buildFamily(samples)));

        log.info("END Scrape for region {} and interval {}", region, intervalSeconds);
        return familySamples;
    }

    private void captureLatency(long timeTaken) {
        gaugeExporter.exportMetric(
                SCRAPE_LATENCY_METRIC, "scraper Instrumentation",
                ImmutableMap.of(
                        SCRAPE_REGION_LABEL, region,
                        SCRAPE_OPERATION_LABEL, "get_metric_data",
                        SCRAPE_INTERVAL_LABEL, intervalSeconds + ""
                ), Instant.now(), timeTaken * 1.0D);
    }

    private Map<String, MetricQuery> mapQueriesById(List<MetricQuery> queries) {
        Map<String, MetricQuery> queriesById = new TreeMap<>();
        queries.forEach(metricQuery ->
                queriesById.put(metricQuery.getMetricDataQuery().id(), metricQuery));
        return queriesById;
    }
}
