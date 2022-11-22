/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws;

import ai.asserts.aws.exporter.BasicMetricCollector;
import com.google.common.collect.ImmutableMap;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;
import software.amazon.awssdk.services.config.ConfigClient;
import software.amazon.awssdk.services.ecs.EcsClient;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.resourcegroupstaggingapi.ResourceGroupsTaggingApiClient;

import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.Semaphore;

import static ai.asserts.aws.MetricNameUtil.ASSERTS_ERROR_TYPE;
import static ai.asserts.aws.MetricNameUtil.SCRAPE_ERROR_COUNT_METRIC;
import static ai.asserts.aws.MetricNameUtil.SCRAPE_LATENCY_METRIC;

@Component
@Slf4j
@AllArgsConstructor
public class RateLimiter {
    private final Semaphore defaultSemaphore = new Semaphore(2);
    private final BasicMetricCollector metricCollector;

    private final Map<String, Semaphore> semaphores = new ImmutableMap.Builder<String, Semaphore>()
            .put(LambdaClient.class.getSimpleName(), new Semaphore(2))
            .put(EcsClient.class.getSimpleName(), new Semaphore(2))
            .put(CloudWatchClient.class.getSimpleName(), new Semaphore(2))
            .put(ResourceGroupsTaggingApiClient.class.getSimpleName(), new Semaphore(2))
            .put(CloudWatchLogsClient.class.getSimpleName(), new Semaphore(1))
            .put(ConfigClient.class.getSimpleName(), new Semaphore(1))
            .build();

    public <K extends AWSAPICall<V>, V> V doWithRateLimit(String api, SortedMap<String, String> labels, K k) {
        Semaphore theSemaphore = semaphores.getOrDefault(api.split("/")[0], defaultSemaphore);
        long tick = System.currentTimeMillis();
        try {
            theSemaphore.acquire();
            tick = System.currentTimeMillis();
            return k.makeCall();
        } catch (Throwable e) {
            log.error("Exception", e);
            SortedMap<String, String> errorLabels = new TreeMap<>(labels);
            errorLabels.put(ASSERTS_ERROR_TYPE, e.getClass().getSimpleName());
            metricCollector.recordCounterValue(SCRAPE_ERROR_COUNT_METRIC, errorLabels, 1);
            throw new RuntimeException(e);
        } finally {
            tick = System.currentTimeMillis() - tick;
            metricCollector.recordLatency(SCRAPE_LATENCY_METRIC, labels, tick);
            theSemaphore.release();
        }
    }

    public interface AWSAPICall<V> {
        V makeCall();
    }
}
