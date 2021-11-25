/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws;

import ai.asserts.aws.cloudwatch.config.ScrapeConfig;
import ai.asserts.aws.cloudwatch.config.ScrapeConfigProvider;
import org.easymock.EasyMockSupport;
import org.junit.jupiter.api.Test;

import static org.easymock.EasyMock.expect;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class CallRateLimiterTest extends EasyMockSupport {
    @Test
    public void acquireTurn() {
        ScrapeConfigProvider scrapeConfigProvider = mock(ScrapeConfigProvider.class);
        ScrapeConfig scrapeConfig = mock(ScrapeConfig.class);
        CallRateLimiter callRateLimiter = new CallRateLimiter(scrapeConfigProvider);

        expect(scrapeConfigProvider.getScrapeConfig()).andReturn(scrapeConfig).anyTimes();
        expect(scrapeConfig.getAwsAPICallsSpacingMillis()).andReturn(1000).anyTimes();

        replayAll();
        callRateLimiter.acquireTurn();
        long t1 = System.currentTimeMillis();
        callRateLimiter.acquireTurn();
        long t2 = System.currentTimeMillis();
        assertTrue(t2 - t1 > 1000);
        verifyAll();
    }
}
