/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws;

import ai.asserts.aws.cloudwatch.config.ScrapeConfig;
import ai.asserts.aws.cloudwatch.config.ScrapeConfigProvider;
import org.easymock.EasyMockSupport;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

import static org.easymock.EasyMock.expect;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class CallRateLimiterTest extends EasyMockSupport {
    @Test
    public void acquireTurn() throws Exception {
        ExecutorService service = Executors.newFixedThreadPool(2);
        ScrapeConfigProvider scrapeConfigProvider = mock(ScrapeConfigProvider.class);
        ScrapeConfig scrapeConfig = mock(ScrapeConfig.class);
        CallRateLimiter callRateLimiter = new CallRateLimiter(scrapeConfigProvider);

        expect(scrapeConfigProvider.getScrapeConfig()).andReturn(scrapeConfig).anyTimes();
        expect(scrapeConfig.getAwsAPICallsSpacingMillis()).andReturn(1000).anyTimes();

        replayAll();
        AtomicLong t1 = new AtomicLong(0);
        AtomicLong t2 = new AtomicLong(0);
        AtomicLong t3 = new AtomicLong(0);

        t1.set(System.currentTimeMillis());
        callRateLimiter.acquireTurn();
        service.submit(() -> {
            callRateLimiter.acquireTurn();
            t2.set(System.currentTimeMillis());
        }).get();
        service.submit(() -> {
            callRateLimiter.acquireTurn();
            t3.set(System.currentTimeMillis());
        }).get();
        assertTrue(t2.get() - t1.get() > 1000);
        assertTrue(t3.get() - t2.get() > 1000);
        verifyAll();
    }
}
