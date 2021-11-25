/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws;

import ai.asserts.aws.cloudwatch.config.ScrapeConfigProvider;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

@Component
@Slf4j
@AllArgsConstructor
public class CallRateLimiter {
    private final ScrapeConfigProvider scrapeConfigProvider;
    private final AtomicLong lastCallTime = new AtomicLong(System.currentTimeMillis());

    public void acquireTurn() {
        long timeSince = System.currentTimeMillis() - lastCallTime.get();
        int spacing = scrapeConfigProvider.getScrapeConfig().getAwsAPICallsSpacingMillis();
        if (timeSince < spacing) {
            try {
                Thread.sleep(spacing - timeSince + 5);
                lastCallTime.set(System.currentTimeMillis());
            } catch (InterruptedException e) {
                log.error("Sleep interrupted", e);
            }
        }
    }
}
