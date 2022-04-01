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
        ZonedDateTime start, end;
        ZonedDateTime now = getZonedDateTime(region);
        end = now.minusSeconds(now.getSecond());
        start = end.minusSeconds(scrapeIntervalSeconds);
        return new Instant[]{start.toInstant(), end.toInstant()};
    }

    public Instant[] getDailyMetricTimeWindow(String region) {

        ZonedDateTime start, end;

        // S3 Storage metrics are available at just before midnight in the region's local time
        // End is 23:59 PM of yesterday
        ZonedDateTime now = getZonedDateTime(region);
        ZonedDateTime previousDay = now.minusDays(1);
        end = ZonedDateTime.of(previousDay.getYear(), previousDay.getMonthValue(), previousDay.getDayOfMonth(), 23, 59, 0, 0, now.getZone());
        start = ZonedDateTime.of(previousDay.getYear(), previousDay.getMonthValue(), previousDay.getDayOfMonth(), 0, 0, 0, 0, now.getZone());
        return new Instant[]{start.toInstant(), end.toInstant()};
    }


    public ZonedDateTime getZonedDateTime(String region) {
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
