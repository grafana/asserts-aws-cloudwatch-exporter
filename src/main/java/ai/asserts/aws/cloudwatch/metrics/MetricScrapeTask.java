/*
 * Copyright © 2021
 * Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.cloudwatch.metrics;

import ai.asserts.aws.AWSClientProvider;
import ai.asserts.aws.cloudwatch.prometheus.GaugeExporter;
import ai.asserts.aws.cloudwatch.query.MetricQuery;
import ai.asserts.aws.cloudwatch.query.MetricQueryProvider;
import ai.asserts.aws.cloudwatch.query.QueryBatcher;
import com.google.common.annotations.VisibleForTesting;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.GetMetricDataRequest;
import software.amazon.awssdk.services.cloudwatch.model.GetMetricDataResponse;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TimerTask;
import java.util.TreeMap;
import java.util.stream.Collectors;

@Slf4j
@Setter
@Getter
@AllArgsConstructor
@EqualsAndHashCode(callSuper = false)
public class MetricScrapeTask extends TimerTask {
    @Autowired
    private MetricQueryProvider metricQueryProvider;
    @Autowired
    private QueryBatcher queryBatcher;
    @Autowired
    private GaugeExporter gaugeExporter;
    @Autowired
    private AWSClientProvider awsClientProvider;
    private final String region;
    private final int intervalSeconds;
    @EqualsAndHashCode.Exclude
    private final int delaySeconds = 60;

    public MetricScrapeTask(String region, int intervalSeconds) {
        this.region = region;
        this.intervalSeconds = intervalSeconds;
    }

    public void run() {
        Instant now = now();

        Map<Integer, List<MetricQuery>> byInterval = metricQueryProvider.getMetricQueries().get(region);
        List<MetricQuery> queries = byInterval.get(intervalSeconds);
        log.info("BEGIN Scrape for region {} and interval {}", region, intervalSeconds);

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

        CloudWatchClient cloudWatchClient = awsClientProvider.getCloudWatchClient(region);
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

                GetMetricDataResponse metricData = cloudWatchClient.getMetricData(requestBuilder.build());
                metricData.metricDataResults().forEach(metricDataResult -> {
                    MetricQuery metricQuery = queriesById.remove(metricDataResult.id());
                    gaugeExporter.exportMetricMeta(region, metricQuery);
                    gaugeExporter.exportMetrics(region, metricQuery, period, metricDataResult);
                });
                nextToken = metricData.nextToken();
            } while (nextToken != null);
        });

        // If no data was returned for any metric, do zero filling
        gaugeExporter.exportZeros(region, startTime, endTime, period, queriesById);

        log.info("END Scrape for region {} and interval {}", region, intervalSeconds);
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
