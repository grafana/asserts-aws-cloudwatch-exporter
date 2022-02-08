/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.cloudwatch;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * Aligns the metric query time window to the clock minute of the timezone of the region. For S3 Daily metrics,
 * aligns the time window to the last window for which metrics would be available
 */
@Component
@Slf4j
public class TimeWindowBuilder {
    public Instant[] getTimePeriod(String region, int scrapeIntervalSeconds) {

        Instant start, end;

        ZonedDateTime now = getZonedDateTime(region);
        end = now.minusSeconds(now.getSecond())
                .toInstant();
        start = end.minusSeconds(scrapeIntervalSeconds);
        return new Instant[]{start, end};
    }

    public Instant[] getDailyMetricTimeWindow(String region) {

        Instant start, end;

        // S3 Storage metrics are available at just before midnight in the region's local time
        // End is 23:59 PM of yesterday
        ZonedDateTime now = getZonedDateTime(region);
        end = now.minusSeconds(now.getSecond())
                .minusMinutes(now.getMinute())
                .minusHours(now.getHour())
                .minusMinutes(1)
                .toInstant();
        start = now.minusSeconds(now.getSecond())
                .minusMinutes(now.getMinute())
                .minusHours(now.getHour())
                .minusDays(1)
                .toInstant();
        return new Instant[]{start, end};
    }

    public Instant getTimeStampInstant(String timestampString) {
        return ZonedDateTime.parse(timestampString).toInstant();
    }

    public Instant getRegionInstant(String region) {
        return getZonedDateTime(region).toInstant();
    }

    private ZonedDateTime getZonedDateTime(String region) {
        // S3 Storage metrics are available at just before midnight in the region's local time
        String timeZoneId = "America/Los_Angeles";
        switch (region) {
            case "us-east-1":
            case "us-east-2":
                timeZoneId = "America/New_York";
        }
        return ZonedDateTime.now(ZoneId.of(timeZoneId));
    }
}
