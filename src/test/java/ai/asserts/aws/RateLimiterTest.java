/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws;

import org.easymock.EasyMockSupport;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class RateLimiterTest extends EasyMockSupport {
    @Test
    public void doWithRateLimit() throws Exception {
        ExecutorService service = Executors.newFixedThreadPool(3);
        RateLimiter rateLimiter = new RateLimiter();

        AtomicLong t1 = new AtomicLong(0);
        AtomicLong t2 = new AtomicLong(0);

        service.submit(() -> rateLimiter.doWithRateLimit("API/", () -> {
            sleep(2000);
            return null;
        }));
        service.submit(() -> rateLimiter.doWithRateLimit("API/", () -> {
            sleep(3000);
            return null;
        }));
        t1.set(System.currentTimeMillis());
        service.submit(() -> rateLimiter.doWithRateLimit("API/", () -> {
            t2.set(System.currentTimeMillis());
            return null;
        })).get();
        long delay = t2.get() - t1.get();
        assertTrue(delay >= 2000);
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
