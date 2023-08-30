/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws;

import ai.asserts.aws.exporter.BasicMetricCollector;
import org.easymock.EasyMockSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

import static ai.asserts.aws.MetricNameUtil.ASSERTS_ERROR_TYPE;
import static ai.asserts.aws.MetricNameUtil.SCRAPE_ERROR_COUNT_METRIC;
import static ai.asserts.aws.MetricNameUtil.SCRAPE_LATENCY_METRIC;
import static org.easymock.EasyMock.anyLong;
import static org.easymock.EasyMock.eq;
import static org.easymock.EasyMock.expectLastCall;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SuppressWarnings("unused")
public class AWSApiCallRateLimiterTest extends EasyMockSupport {
    private BasicMetricCollector metricCollector;
    private SortedMap<String, String> labels;
    private AWSApiCallRateLimiter rateLimiter;

    @BeforeEach
    public void setup() {
        metricCollector = mock(BasicMetricCollector.class);
        labels = new TreeMap<>();
        labels.put("account_id", "account");
        labels.put("region", "region");
        labels.put("asserts_customer", "acme");
        rateLimiter = new AWSApiCallRateLimiter(metricCollector,
                (accountId) -> "acme", 1.0D);
    }

    @Test
    public void doWithRateLimit() throws Exception {
        ExecutorService service = Executors.newFixedThreadPool(3);

        AtomicLong t1 = new AtomicLong(0);
        AtomicLong t2 = new AtomicLong(0);

        metricCollector.recordLatency(eq(SCRAPE_LATENCY_METRIC), eq(labels), anyLong());
        expectLastCall().times(2);

        replayAll();

        String api = "Client/API";
        service.submit(() -> rateLimiter.doWithRateLimit(api, labels,
                () -> {
                    t1.set(System.currentTimeMillis());
                    return null;
                })).get();
        service.submit(() -> rateLimiter.doWithRateLimit(api, labels, () -> {
            t2.set(System.currentTimeMillis());
            return null;
        })).get();

        long delay = t2.get() - t1.get();
        assertTrue(delay >= 1000);
        verifyAll();
    }

    private void sleep() {
        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }
}
