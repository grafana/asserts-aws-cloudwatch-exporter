/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.cloudwatch;

import ai.asserts.aws.cloudwatch.TimeWindowBuilder;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalField;
import java.time.temporal.TemporalUnit;
import java.time.zone.ZoneRules;
import java.util.Date;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class TimeWindowBuilderTest {
    @Test
    void getTimePeriod_us_west_2() {
        TimeWindowBuilder testClass = new TimeWindowBuilder();
        Instant[] timePeriod = testClass.getTimePeriod("us-west-2", 60);
        ZonedDateTime zonedDateTime = ZonedDateTime.ofInstant(timePeriod[0], ZoneId.of("America/Los_Angeles"));
        assertEquals(0, zonedDateTime.getSecond());
        assertEquals(60_000, timePeriod[1].toEpochMilli() - timePeriod[0].toEpochMilli());
    }

    @Test
    void getDailyTimePeriod_us_west_2() {
        TimeWindowBuilder testClass = new TimeWindowBuilder();
        Instant[] timePeriod = testClass.getDailyMetricTimeWindow("us-west-2");
        ZonedDateTime currentTime = testClass.getZonedDateTime("us-west-2");
        ZoneRules zoneRules = currentTime.getZone().getRules();
        if( zoneRules.isDaylightSavings( currentTime.toInstant() )){
            timePeriod[0] = timePeriod[0].plus(1, ChronoUnit.HOURS);
            timePeriod[1] = timePeriod[1].plus(1, ChronoUnit.HOURS);
        }
        ZonedDateTime startTime = ZonedDateTime.ofInstant(timePeriod[0], ZoneId.of("America/Los_Angeles"));
        ZonedDateTime endTime = ZonedDateTime.ofInstant(timePeriod[1], ZoneId.of("America/Los_Angeles"));
        assertEquals(0, startTime.getSecond());
        assertEquals(0, startTime.getMinute());
        assertEquals(0, startTime.getHour());

        assertEquals(0, endTime.getSecond());
        assertEquals(59, endTime.getMinute());
        assertEquals(23, endTime.getHour());
        assertEquals(startTime.getYear(), endTime.getYear());
        assertEquals(startTime.getDayOfYear(), endTime.getDayOfYear());
    }
}
