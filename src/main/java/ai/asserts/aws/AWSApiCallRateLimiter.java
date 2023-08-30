/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws;

import ai.asserts.aws.account.AccountTenantMapper;
import ai.asserts.aws.exporter.BasicMetricCollector;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.util.concurrent.RateLimiter;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.Callable;

import static ai.asserts.aws.MetricNameUtil.ASSERTS_ERROR_TYPE;
import static ai.asserts.aws.MetricNameUtil.ASSERTS_CUSTOMER;
import static ai.asserts.aws.MetricNameUtil.SCRAPE_ACCOUNT_ID_LABEL;
import static ai.asserts.aws.MetricNameUtil.SCRAPE_ERROR_COUNT_METRIC;
import static ai.asserts.aws.MetricNameUtil.SCRAPE_LATENCY_METRIC;
import static ai.asserts.aws.MetricNameUtil.SCRAPE_OPERATION_LABEL;
import static ai.asserts.aws.MetricNameUtil.SCRAPE_REGION_LABEL;
import static ai.asserts.aws.MetricNameUtil.TENANT;
import static java.lang.String.format;
import static java.util.stream.Collectors.joining;

@Slf4j
@SuppressWarnings("UnstableApiUsage")
public class AWSApiCallRateLimiter {
    private final BasicMetricCollector metricCollector;
    private final AccountTenantMapper accountTenantMapper;
    private final double defaultRateLimit;

    private final ThreadLocal<Map<String, Integer>> apiCallCounts = ThreadLocal.withInitial(TreeMap::new);

    private final Map<String, RateLimiter> rateLimiters = new HashMap<>();

    @VisibleForTesting
    public AWSApiCallRateLimiter(BasicMetricCollector metricCollector, AccountTenantMapper accountTenantMapper) {
        this(metricCollector, accountTenantMapper, 20);
    }

    public AWSApiCallRateLimiter(BasicMetricCollector metricCollector, AccountTenantMapper accountTenantMapper,
                                 double defaultRateLimit) {
        this.metricCollector = metricCollector;
        this.accountTenantMapper = accountTenantMapper;
        this.defaultRateLimit = defaultRateLimit;
    }

    public <K extends AWSAPICall<V>, V> V doWithRateLimit(String api, SortedMap<String, String> labels, K k) {
        String accountId = labels.get(SCRAPE_ACCOUNT_ID_LABEL);
        String region = labels.get(SCRAPE_REGION_LABEL);
        String regionKey = accountId + "/" + region;
        String fullKey = regionKey + "/" + api;
        RateLimiter rateLimiter = rateLimiters.computeIfAbsent(fullKey, s -> RateLimiter.create(defaultRateLimit));
        long tick = System.currentTimeMillis();
        String tenantName = accountTenantMapper.getTenantName(labels.get(SCRAPE_ACCOUNT_ID_LABEL));
        try {
            double waitTime = rateLimiter.acquire();
            if (waitTime > 0.5) {
                log.warn("Operation {} throttled for {} seconds", fullKey, waitTime);
            }
            Map<String, Integer> callCounts = apiCallCounts.get();
            String operationName = labels.getOrDefault(SCRAPE_OPERATION_LABEL, "unknown");
            String callCountKey = regionKey + "/" + operationName;
            Integer count = callCounts.getOrDefault(callCountKey, 0);
            count++;
            callCounts.put(callCountKey, count);
            tick = System.currentTimeMillis();
            return k.makeCall();
        } catch (Throwable e) {
            log.error("Exception in: " + regionKey, e);
            SortedMap<String, String> errorLabels = new TreeMap<>(labels);
            errorLabels.put(ASSERTS_ERROR_TYPE, e.getClass().getSimpleName());

            // In SaaS mode, we don't want the exporter internal metrics to end up in the tenant's TSDB
            errorLabels.remove(TENANT);
            if (tenantName != null) {
                errorLabels.put(ASSERTS_CUSTOMER, tenantName);
            }
            metricCollector.recordCounterValue(SCRAPE_ERROR_COUNT_METRIC, errorLabels, 1);
            throw new RuntimeException(e);
        } finally {
            tick = System.currentTimeMillis() - tick;

            // In SaaS mode, we don't want the exporter internal metrics to end up in the tenant's TSDB
            SortedMap<String, String> latencyLabels = new TreeMap<>(labels);
            latencyLabels.remove(TENANT);
            if (tenantName != null) {
                latencyLabels.put(ASSERTS_CUSTOMER, tenantName);
            }
            metricCollector.recordLatency(SCRAPE_LATENCY_METRIC, latencyLabels, tick);
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
