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
import com.google.common.annotations.VisibleForTesting;
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
import java.util.stream.Collectors;

import static ai.asserts.aws.MetricNameUtil.SCRAPE_INTERVAL_LABEL;
import static ai.asserts.aws.MetricNameUtil.SCRAPE_LATENCY_METRIC;
import static ai.asserts.aws.MetricNameUtil.SCRAPE_OPERATION_LABEL;
import static ai.asserts.aws.MetricNameUtil.SCRAPE_REGION_LABEL;
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
    @EqualsAndHashCode.Include
    private final String region;
    @EqualsAndHashCode.Include
    private final int intervalSeconds;
    @EqualsAndHashCode.Include
    private final int delaySeconds;

    private long lastScrapeTime = -1;

    public MetricScrapeTask(String region, int intervalSeconds, int delay) {
        this.region = region;
        this.intervalSeconds = intervalSeconds;
        this.delaySeconds = delay;
    }

    @Override
    public List<MetricFamilySamples> collect() {
        List<MetricFamilySamples> familySamples = new ArrayList<>();

        Instant now = now();
        long timeSinceLastScrape = now.toEpochMilli() - lastScrapeTime;
        if (timeSinceLastScrape < (intervalSeconds - 5) * 1000L) {
            return familySamples;
        }
        lastScrapeTime = now.toEpochMilli();

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

        // The result only has the query id. We will need the metric while processing the result
        // so build a map for lookup
        Map<String, MetricQuery> queriesById = mapQueriesById(queries);

        List<List<MetricQuery>> batches = queryBatcher.splitIntoBatches(queries);
        log.info("Split metric queries into {} batches", batches.size());

        Integer period = queriesById.entrySet().iterator().next().getValue().getMetricConfig().getPeriod();

        // If interval > period we will get interval/period samples
        // If period > interval, we get a sample for each scrape that aggregates data
        // between (t1, t2) where t2 = now - delay and t1 = t2 - period
        Instant endTime = now.minusSeconds(delaySeconds);
        Instant startTime = endTime.minusSeconds(Math.max(intervalSeconds, period));

        Map<String, List<MetricFamilySamples.Sample>> samplesByMetric = new TreeMap<>();

        try (CloudWatchClient cloudWatchClient = awsClientProvider.getCloudWatchClient(region)) {
            batches.forEach(batch -> {
                String nextToken = null;
                do {
                    GetMetricDataRequest.Builder requestBuilder = GetMetricDataRequest.builder()
                            .endTime(endTime)
                            .startTime(startTime)
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
                                            metricQuery, metricDataResult, period);

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

    @VisibleForTesting
    Instant now() {
        return Instant.now();
    }

    private Map<String, MetricQuery> mapQueriesById(List<MetricQuery> queries) {
        Map<String, MetricQuery> queriesById = new TreeMap<>();
        queries.forEach(metricQuery ->
                queriesById.put(metricQuery.getMetricDataQuery().id(), metricQuery));
        return queriesById;
    }
}
