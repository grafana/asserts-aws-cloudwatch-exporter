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

import static ai.asserts.aws.MetricNameUtil.SCRAPE_ERROR_COUNT_METRIC;
import static ai.asserts.aws.MetricNameUtil.SCRAPE_LATENCY_METRIC;
import static org.easymock.EasyMock.anyLong;
import static org.easymock.EasyMock.eq;
import static org.easymock.EasyMock.expectLastCall;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class RateLimiterTest extends EasyMockSupport {
    private BasicMetricCollector metricCollector;
    private SortedMap<String, String> labels;
    private RateLimiter rateLimiter;

    @BeforeEach
    public void setup() {
        metricCollector = mock(BasicMetricCollector.class);
        labels = new TreeMap<>();
        rateLimiter = new RateLimiter(metricCollector);
    }

    @Test
    public void doWithRateLimit() throws Exception {
        ExecutorService service = Executors.newFixedThreadPool(3);

        AtomicLong t1 = new AtomicLong(0);
        AtomicLong t2 = new AtomicLong(0);

        metricCollector.recordLatency(eq(SCRAPE_LATENCY_METRIC), eq(labels), anyLong());
        expectLastCall().times(2);

        replayAll();

        service.submit(() -> rateLimiter.doWithRateLimit("API/", labels, () -> {
            sleep(2000);
            return null;
        }));
        service.submit(() -> rateLimiter.doWithRateLimit("API/", labels, () -> {
            sleep(3000);
            return null;
        }));

        // The third call should block for a while until a semaphore becomes free
        t1.set(System.currentTimeMillis());
        service.submit(() -> rateLimiter.doWithRateLimit("API/", labels, () -> {
            t2.set(System.currentTimeMillis());
            return null;
        })).get();
        long delay = t2.get() - t1.get();
        assertTrue(delay >= 2000);
        verifyAll();
    }

    @Test
    public void doWithRateLimit_WithError() throws Exception {
        ExecutorService service = Executors.newFixedThreadPool(3);

        metricCollector.recordLatency(eq(SCRAPE_LATENCY_METRIC), eq(labels), anyLong());
        expectLastCall().times(2);
        metricCollector.recordCounterValue(SCRAPE_ERROR_COUNT_METRIC, labels, 1);

        replayAll();

        service.submit(() -> rateLimiter.doWithRateLimit("API/", labels, () -> {
            return null;
        }));

        try {
            service.submit(() -> rateLimiter.doWithRateLimit("API/",
                    labels, () -> {
                        throw new RuntimeException();
                    })).get();
        } catch (Exception e) {
            e.printStackTrace();
        }

        verifyAll();
    }

    private void sleep(int millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }
}
