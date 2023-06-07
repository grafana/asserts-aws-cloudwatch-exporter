/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws;

import ai.asserts.aws.account.AccountTenantMapper;
import ai.asserts.aws.exporter.BasicMetricCollector;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.Semaphore;

import static ai.asserts.aws.MetricNameUtil.ASSERTS_ERROR_TYPE;
import static ai.asserts.aws.MetricNameUtil.SCRAPE_ACCOUNT_ID_LABEL;
import static ai.asserts.aws.MetricNameUtil.SCRAPE_ERROR_COUNT_METRIC;
import static ai.asserts.aws.MetricNameUtil.SCRAPE_LATENCY_METRIC;
import static ai.asserts.aws.MetricNameUtil.SCRAPE_OPERATION_LABEL;
import static ai.asserts.aws.MetricNameUtil.SCRAPE_REGION_LABEL;
import static ai.asserts.aws.MetricNameUtil.TENANT;
import static java.lang.String.format;
import static java.util.stream.Collectors.joining;

@Component
@Slf4j
@AllArgsConstructor
public class RateLimiter {
    private final BasicMetricCollector metricCollector;
    private final AccountTenantMapper accountTenantMapper;

    private final ThreadLocal<Map<String, Integer>> apiCallCounts = ThreadLocal.withInitial(TreeMap::new);

    private final Map<String, Semaphore> semaphores = new LinkedHashMap<>();

    public <K extends AWSAPICall<V>, V> V doWithRateLimit(String api, SortedMap<String, String> labels, K k) {
        String accountId = labels.get(SCRAPE_ACCOUNT_ID_LABEL);
        String region = labels.get(SCRAPE_REGION_LABEL);
        String clientType = api.split("/")[0];
        String regionKey = accountId + "/" + region;
        String fullKey = regionKey + "/" + clientType;
        Semaphore theSemaphore = semaphores.computeIfAbsent(fullKey, s -> new Semaphore(2));
        long tick = System.currentTimeMillis();
        try {
            theSemaphore.acquire();
            Map<String, Integer> callCounts = apiCallCounts.get();
            String operationName = labels.getOrDefault(SCRAPE_OPERATION_LABEL, "unknown");
            String callCountKey = regionKey + "/" + operationName;
            Integer count = callCounts.getOrDefault(callCountKey, 0);
            count++;
            callCounts.put(callCountKey, count);
            tick = System.currentTimeMillis();
            return k.makeCall();
        } catch (Throwable e) {
            log.error("Exception", e);
            SortedMap<String, String> errorLabels = new TreeMap<>(labels);
            errorLabels.put(ASSERTS_ERROR_TYPE, e.getClass().getSimpleName());
            errorLabels.put(TENANT, accountTenantMapper.getTenantName(labels.get(SCRAPE_ACCOUNT_ID_LABEL)));
            metricCollector.recordCounterValue(SCRAPE_ERROR_COUNT_METRIC, errorLabels, 1);
            throw new RuntimeException(e);
        } finally {
            tick = System.currentTimeMillis() - tick;
            metricCollector.recordLatency(SCRAPE_LATENCY_METRIC, labels, tick);
            theSemaphore.release();
        }
    }

    public <T> T call(Callable<T> callable) throws Exception {
        try {
            return callable.call();
        } finally {
            logAPICallCountsAndClear();
        }
    }

    public void logAPICallCountsAndClear() {
        log.info("AWS API Call Counts \n\n{}\n",
                apiCallCounts.get().entrySet().stream()
                        .map(entry -> format("%s=%s", entry.getKey(), entry.getValue()))
                        .collect(joining("\n")));
        apiCallCounts.get().clear();
    }

    public interface AWSAPICall<V> {
        V makeCall();
    }
}
