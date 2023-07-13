/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.lambda;

import ai.asserts.aws.AWSApiCallRateLimiter;
import ai.asserts.aws.cloudwatch.TimeWindowBuilder;
import ai.asserts.aws.config.LogScrapeConfig;
import ai.asserts.aws.exporter.BasicMetricCollector;
import com.google.common.collect.ImmutableList;
import org.easymock.EasyMockSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;
import software.amazon.awssdk.services.cloudwatchlogs.model.FilterLogEventsRequest;
import software.amazon.awssdk.services.cloudwatchlogs.model.FilterLogEventsResponse;
import software.amazon.awssdk.services.cloudwatchlogs.model.FilteredLogEvent;

import java.time.Instant;
import java.util.Optional;
import java.util.SortedMap;

import static ai.asserts.aws.MetricNameUtil.SCRAPE_ERROR_COUNT_METRIC;
import static ai.asserts.aws.MetricNameUtil.SCRAPE_LATENCY_METRIC;
import static org.easymock.EasyMock.anyLong;
import static org.easymock.EasyMock.anyObject;
import static org.easymock.EasyMock.eq;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.expectLastCall;
import static org.junit.jupiter.api.Assertions.assertEquals;

@SuppressWarnings("unchecked")
public class LogEventScraperTest extends EasyMockSupport {
    private CloudWatchLogsClient cloudWatchLogsClient;
    private LambdaFunction lambdaFunction;
    private LogScrapeConfig logScrapeConfig;
    private BasicMetricCollector metricCollector;
    private Instant now;
    private TimeWindowBuilder timeWindowBuilder;
    private LogEventScraper testClass;

    @BeforeEach
    public void setup() {
        cloudWatchLogsClient = mock(CloudWatchLogsClient.class);
        lambdaFunction = mock(LambdaFunction.class);
        logScrapeConfig = mock(LogScrapeConfig.class);
        metricCollector = mock(BasicMetricCollector.class);
        timeWindowBuilder = mock(TimeWindowBuilder.class);

        now = Instant.now();
        testClass = new LogEventScraper(timeWindowBuilder, new AWSApiCallRateLimiter(metricCollector,
                (accountId) -> "tenant"));
    }

    @Test
    public void findLogEvent() {
        expect(timeWindowBuilder.getTimePeriod("region1", 60)).andReturn(new Instant[]{now.minusSeconds(60), now});
        FilterLogEventsRequest request = FilterLogEventsRequest.builder()
                .limit(1)
                .endTime(now.minusSeconds(60).toEpochMilli())
                .startTime(now.minusSeconds(120).toEpochMilli())
                .logGroupName("/aws/lambda/function-1")
                .filterPattern("filterPattern")
                .build();

        FilteredLogEvent filteredLogEvent = FilteredLogEvent.builder()
                .message("message")
                .build();

        FilterLogEventsResponse response = FilterLogEventsResponse.builder()
                .events(ImmutableList.of(filteredLogEvent))
                .build();

        expect(lambdaFunction.getName()).andReturn("function-1").anyTimes();
        expect(lambdaFunction.getRegion()).andReturn("region1").anyTimes();
        expect(lambdaFunction.getAccount()).andReturn("account").anyTimes();
        expect(logScrapeConfig.getLogFilterPattern()).andReturn("filterPattern");
        expect(cloudWatchLogsClient.filterLogEvents(request)).andReturn(response);
        metricCollector.recordLatency(eq(SCRAPE_LATENCY_METRIC), anyObject(SortedMap.class), anyLong());
        expectLastCall();
        replayAll();
        assertEquals(
                Optional.of(filteredLogEvent),
                testClass.findLogEvent(cloudWatchLogsClient, lambdaFunction, logScrapeConfig)
        );
        verifyAll();
    }

    @Test
    public void findLogEvent_Exception() {
        expect(timeWindowBuilder.getTimePeriod("region1", 60)).andReturn(new Instant[]{now.minusSeconds(60), now});
        FilterLogEventsRequest request = FilterLogEventsRequest.builder()
                .limit(1)
                .endTime(now.minusSeconds(60).toEpochMilli())
                .startTime(now.minusSeconds(120).toEpochMilli())
                .logGroupName("/aws/lambda/function-1")
                .filterPattern("filterPattern")
                .build();

        expect(lambdaFunction.getName()).andReturn("function-1").anyTimes();
        expect(lambdaFunction.getRegion()).andReturn("region1").anyTimes();
        expect(lambdaFunction.getAccount()).andReturn("account").anyTimes();
        expect(logScrapeConfig.getLogFilterPattern()).andReturn("filterPattern");
        expect(cloudWatchLogsClient.filterLogEvents(request)).andThrow(new RuntimeException());
        metricCollector.recordCounterValue(eq(SCRAPE_ERROR_COUNT_METRIC), anyObject(SortedMap.class), eq(1));
        metricCollector.recordLatency(eq(SCRAPE_LATENCY_METRIC), anyObject(SortedMap.class), anyLong());
        replayAll();
        assertEquals(
                Optional.empty(),
                testClass.findLogEvent(cloudWatchLogsClient, lambdaFunction, logScrapeConfig)
        );
        verifyAll();
    }
}
