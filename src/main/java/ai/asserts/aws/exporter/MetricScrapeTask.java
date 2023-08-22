
package ai.asserts.aws.exporter;

import ai.asserts.aws.AWSClientProvider;
import ai.asserts.aws.AWSApiCallRateLimiter;
import ai.asserts.aws.SimpleTenantTask;
import ai.asserts.aws.TaskExecutorUtil;
import ai.asserts.aws.account.AWSAccount;
import ai.asserts.aws.cloudwatch.TimeWindowBuilder;
import ai.asserts.aws.cloudwatch.query.MetricQuery;
import ai.asserts.aws.cloudwatch.query.MetricQueryProvider;
import ai.asserts.aws.cloudwatch.query.QueryBatcher;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;
import io.prometheus.client.Collector;
import io.prometheus.client.CollectorRegistry;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

import static ai.asserts.aws.MetricNameUtil.SCRAPE_ACCOUNT_ID_LABEL;
import static ai.asserts.aws.MetricNameUtil.SCRAPE_INTERVAL_LABEL;
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
 */
@Slf4j
@Setter
@Getter
@EqualsAndHashCode(callSuper = false,
        of = {"account", "region", "intervalSeconds", "delaySeconds"})
@ToString(of = {"account", "region", "intervalSeconds", "delaySeconds"})
public class MetricScrapeTask extends Collector implements MetricProvider {
    @Autowired
    private AWSClientProvider awsClientProvider;
    @Autowired
    private MetricQueryProvider metricQueryProvider;
    @Autowired
    private QueryBatcher queryBatcher;
    @Autowired
    private MetricSampleBuilder sampleBuilder;
    @Autowired
    private CollectorRegistry collectorRegistry;
    @Autowired
    private TimeWindowBuilder timeWindowBuilder;
    @Autowired
    private AWSApiCallRateLimiter rateLimiter;
    @Autowired
    private TaskExecutorUtil taskExecutorUtil;

    private final AWSAccount account;
    private final String region;
    private final int intervalSeconds;
    private final int delaySeconds;
    private long lastRunTime = -1;
    private volatile List<MetricFamilySamples> cache;

    public MetricScrapeTask(AWSAccount account, String region, int intervalSeconds, int delay) {
        this.account = account;
        this.region = region;
        this.intervalSeconds = intervalSeconds;
        this.delaySeconds = delay;
        this.cache = new ArrayList<>();
    }

    @Override
    public List<MetricFamilySamples> collect() {
        return cache;
    }

    @Override
    public void update() {
        if (intervalSeconds <= 60 || System.currentTimeMillis() - lastRunTime > intervalSeconds * 1000L) {
            lastRunTime = System.currentTimeMillis();
            try {
                cache = taskExecutorUtil.executeAccountTask(account,
                        new SimpleTenantTask<List<MetricFamilySamples>>() {
                            @Override
                            public List<MetricFamilySamples> call() {
                                try {
                                    return fetchMetricsFromCW();
                                } catch (Exception e) {
                                    log.error("Failed to update", e);
                                }
                                return Collections.emptyList();
                            }
                        }).get(15, TimeUnit.SECONDS);
            } catch (InterruptedException | ExecutionException | TimeoutException e) {
                log.error("Failed to fetch metrics", e);
            }
        }
    }

    private List<MetricFamilySamples> fetchMetricsFromCW() {
        List<MetricFamilySamples> familySamples = new ArrayList<>();
        log.debug("BEGIN Scrape for account={} region={} and interval={}", account, region, intervalSeconds);
        Map<String, Map<Integer, List<MetricQuery>>> byRegion = metricQueryProvider.getMetricQueries()
                .getOrDefault(account.getAccountId(), ImmutableMap.of());
        Map<Integer, List<MetricQuery>> byInterval = byRegion.get(region);
        if (byInterval == null) {
            log.error("No queries found for account = {}, region = {}", account, region);
            return Collections.emptyList();
        }

        List<MetricQuery> queries = byInterval.get(intervalSeconds);
        if (queries == null) {
            log.error("No queries found for region {} and interval {}", region, intervalSeconds);
            return Collections.emptyList();
        }
        boolean s3DailyMetric = queries.stream().anyMatch(this::isS3DailyMetric);

        // The result only has the query id. We will need the metric while processing the result
        // so build a map for lookup
        Map<String, MetricQuery> queriesById = mapQueriesById(queries);

        List<List<MetricQuery>> batches = queryBatcher.splitIntoBatches(queries);
        log.debug("Split metric queries into {} batches", batches.size());

        Map<String, List<MetricFamilySamples.Sample>> samplesByMetric = new TreeMap<>();

        try {
            CloudWatchClient cloudWatchClient = awsClientProvider.getCloudWatchClient(region, account);
            batches.forEach(batch -> {
                String nextToken = null;
                // For now, S3 is the only one which has some metrics with a period of 1 day.
                // These metrics should be configured with a different interval
                Instant[] timePeriod = s3DailyMetric ? timeWindowBuilder.getDailyMetricTimeWindow(region) :
                        timeWindowBuilder.getTimePeriod(region, intervalSeconds);
                log.debug("Scraping metrics for time period {} - {}", timePeriod[0], timePeriod[1]);
                do {
                    GetMetricDataRequest.Builder requestBuilder = GetMetricDataRequest.builder()
                            .startTime(timePeriod[0].minusSeconds(delaySeconds))
                            .endTime(timePeriod[1].minusSeconds(delaySeconds))
                            .nextToken(nextToken)
                            .metricDataQueries(batch.stream()
                                    .map(MetricQuery::getMetricDataQuery)
                                    .collect(Collectors.toList()));

                    GetMetricDataRequest req = requestBuilder.build();
                    String operationName = "CloudWatchClient/getMetricData";
                    GetMetricDataResponse metricData = rateLimiter.doWithRateLimit(
                            operationName,
                            ImmutableSortedMap.of(
                                    SCRAPE_ACCOUNT_ID_LABEL, account.getAccountId(),
                                    SCRAPE_REGION_LABEL, region,
                                    SCRAPE_OPERATION_LABEL, operationName,
                                    SCRAPE_INTERVAL_LABEL, intervalSeconds + ""
                            ),
                            () -> cloudWatchClient.getMetricData(req));

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
                                    List<MetricFamilySamples.Sample> samples = sampleBuilder.buildSamples(
                                            account.getAccountId(), region, metricQuery, metricDataResult);

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
            log.debug("Got samples for {}", samplesByMetric.keySet());
        } else {
            log.debug("Didn't find any samples for region {} and interval {}", region, intervalSeconds);
        }
        samplesByMetric.forEach((metricName, samples) ->
                sampleBuilder.buildFamily(samples).ifPresent(familySamples::add));

        log.debug("END Scrape for region {} and interval {}", region, intervalSeconds);
        return familySamples;
    }

    @VisibleForTesting
    boolean isS3DailyMetric(MetricQuery metricQuery) {
        String metricName = metricQuery.getMetric().metricName();
        return "AWS/S3".equals(metricQuery.getMetric().namespace()) && (metricName.equals(
                "BucketSizeBytes") || metricName.equals("NumberOfObjects"));
    }

    private Map<String, MetricQuery> mapQueriesById(List<MetricQuery> queries) {
        Map<String, MetricQuery> queriesById = new TreeMap<>();
        queries.forEach(metricQuery ->
                queriesById.put(metricQuery.getMetricDataQuery().id(), metricQuery));
        return queriesById;
    }
}
