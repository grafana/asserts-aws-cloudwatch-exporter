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
        try {
            semaphore.acquire();
            long currentTime = System.currentTimeMillis();
            Integer delay = scrapeConfigProvider.getScrapeConfig().getAwsAPICallsSpacingMillis();
            long elapsed = currentTime - lastCallTime;
            if (elapsed < delay) {
                lastCallTime = lastCallTime + delay;
                semaphore.release();
                Thread.sleep(delay - elapsed);
            } else {
                lastCallTime = System.currentTimeMillis();
                semaphore.release();
            }
        } catch (InterruptedException e) {
            log.error("Sleep interrupted", e);
        }
    }
}
