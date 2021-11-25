/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws;

import ai.asserts.aws.cloudwatch.config.ScrapeConfigProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.Semaphore;

@Component
@Slf4j
public class CallRateLimiter {
    private final ScrapeConfigProvider scrapeConfigProvider;
    private long lastCallTime = 0L;
    private final Semaphore semaphore = new Semaphore(1);

    public CallRateLimiter(ScrapeConfigProvider scrapeConfigProvider) {
        this.scrapeConfigProvider = scrapeConfigProvider;
    }

    public void acquireTurn() {
        Integer delay = scrapeConfigProvider.getScrapeConfig().getAwsAPICallsSpacingMillis();
        try {
            semaphore.acquire();
            long currentTime = System.currentTimeMillis();
            long allowedCallTime = lastCallTime + delay + 10;
            lastCallTime = Math.max(allowedCallTime, currentTime);
            semaphore.release();
            if (currentTime < allowedCallTime) {
                Thread.sleep(allowedCallTime - currentTime);
            }
        } catch (InterruptedException e) {
            log.error("Sleep interrupted", e);
        }
    }
}
