/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.lambda;

import ai.asserts.aws.cloudwatch.config.LogScrapeConfig;
import ai.asserts.aws.cloudwatch.prometheus.GaugeExporter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedMap;
import org.easymock.EasyMockSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;
import software.amazon.awssdk.services.cloudwatchlogs.model.FilterLogEventsRequest;
import software.amazon.awssdk.services.cloudwatchlogs.model.FilterLogEventsResponse;
import software.amazon.awssdk.services.cloudwatchlogs.model.FilteredLogEvent;

import java.time.Instant;
import java.util.Optional;

import static ai.asserts.aws.MetricNameUtil.SCRAPE_FUNCTION_NAME_LABEL;
import static ai.asserts.aws.MetricNameUtil.SCRAPE_LATENCY_METRIC;
import static ai.asserts.aws.MetricNameUtil.SCRAPE_OPERATION_LABEL;
import static ai.asserts.aws.MetricNameUtil.SCRAPE_REGION_LABEL;
import static org.easymock.EasyMock.anyDouble;
import static org.easymock.EasyMock.anyObject;
import static org.easymock.EasyMock.anyString;
import static org.easymock.EasyMock.eq;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.expectLastCall;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class LogEventScraperTest extends EasyMockSupport {
    private CloudWatchLogsClient cloudWatchLogsClient;
    private LambdaFunction lambdaFunction;
    private LogScrapeConfig logScrapeConfig;
    private GaugeExporter gaugeExporter;
    private Instant now;
    private LogEventScraper testClass;

    @BeforeEach
    public void setup() {
        cloudWatchLogsClient = mock(CloudWatchLogsClient.class);
        lambdaFunction = mock(LambdaFunction.class);
        logScrapeConfig = mock(LogScrapeConfig.class);
        gaugeExporter = mock(GaugeExporter.class);
        now = Instant.now();
        testClass = new LogEventScraper(gaugeExporter) {
            @Override
            Instant now() {
                return now;
            }
        };
    }

    @Test
    public void findLogEvent() {
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
        expect(logScrapeConfig.getLogFilterPattern()).andReturn("filterPattern");
        expect(cloudWatchLogsClient.filterLogEvents(request)).andReturn(response);
        gaugeExporter.exportMetric(eq(SCRAPE_LATENCY_METRIC), anyString(), eq(ImmutableSortedMap.of(
                SCRAPE_REGION_LABEL, "region1",
                SCRAPE_OPERATION_LABEL, "scrape_lambda_logs",
                SCRAPE_FUNCTION_NAME_LABEL, "function-1"
        )), anyObject(), anyDouble());
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
        expect(logScrapeConfig.getLogFilterPattern()).andReturn("filterPattern");
        expect(cloudWatchLogsClient.filterLogEvents(request)).andThrow(new RuntimeException());
        replayAll();
        assertEquals(
                Optional.empty(),
                testClass.findLogEvent(cloudWatchLogsClient, lambdaFunction, logScrapeConfig)
        );
        verifyAll();
    }
}
