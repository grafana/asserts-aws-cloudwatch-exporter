/*
 *  Copyright © 2021.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.cloudwatch.alarms;

import ai.asserts.aws.AWSClientProvider;
import ai.asserts.aws.RateLimiter;
import ai.asserts.aws.cloudwatch.config.ScrapeConfig;
import ai.asserts.aws.cloudwatch.config.ScrapeConfigProvider;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import org.easymock.Capture;
import org.easymock.EasyMockSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.DescribeAlarmsRequest;
import software.amazon.awssdk.services.cloudwatch.model.DescribeAlarmsResponse;
import software.amazon.awssdk.services.cloudwatch.model.MetricAlarm;
import software.amazon.awssdk.services.cloudwatch.model.StateValue;

import java.time.Instant;
import java.util.SortedMap;

import static org.easymock.EasyMock.anyObject;
import static org.easymock.EasyMock.capture;
import static org.easymock.EasyMock.eq;
import static org.easymock.EasyMock.expect;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class AlarmFetcherTest extends EasyMockSupport {

    private RateLimiter rateLimiter;
    private AWSClientProvider awsClientProvider;
    private ScrapeConfigProvider scrapeConfigProvider;
    private AlertsProcessor alertsProcessor;
    private ScrapeConfig scrapeConfig;
    private CloudWatchClient cloudWatchClient;
    private AlarmFetcher testClass;
    private Instant now;

    @BeforeEach
    public void setup() {
        now = Instant.now();
        scrapeConfigProvider = mock(ScrapeConfigProvider.class);
        scrapeConfig = mock(ScrapeConfig.class);
        alertsProcessor = mock(AlertsProcessor.class);
        awsClientProvider = mock(AWSClientProvider.class);
        rateLimiter = mock(RateLimiter.class);
        cloudWatchClient = mock(CloudWatchClient.class);
        testClass = new AlarmFetcher(rateLimiter, awsClientProvider, scrapeConfigProvider, alertsProcessor);
    }

    @Test
    public void sendAlarmsForRegions() {
        expect(scrapeConfigProvider.getScrapeConfig()).andReturn(scrapeConfig);
        expect(scrapeConfig.getRegions()).andReturn(ImmutableSet.of("region")).anyTimes();
        expect(awsClientProvider.getCloudWatchClient("region")).andReturn(cloudWatchClient).anyTimes();

        Capture<RateLimiter.AWSAPICall<DescribeAlarmsResponse>> callbackCapture = Capture.newInstance();

        DescribeAlarmsResponse response = DescribeAlarmsResponse.builder()
                .metricAlarms(ImmutableList.of(
                        MetricAlarm.builder()
                                .alarmName("alarm1")
                                .stateValue("ALARM")
                                .stateUpdatedTimestamp(now)
                                .threshold(10.0)
                                .namespace("AWS/RDS")
                                .build()))
                .build();

        DescribeAlarmsRequest request = DescribeAlarmsRequest.builder()
                .stateValue(StateValue.ALARM)
                .nextToken(null)
                .build();
        expect(cloudWatchClient.describeAlarms(request)).andReturn(response);
        cloudWatchClient.close();

        expect(rateLimiter.doWithRateLimit(eq("CloudWatchClient/describeAlarms"),
                anyObject(SortedMap.class), capture(callbackCapture))).andReturn(response);
        alertsProcessor.sendAlerts(ImmutableList.of(ImmutableMap.of()));
        replayAll();
        testClass.sendAlarmsForRegions();
        assertEquals(response, callbackCapture.getValue().makeCall());

        verifyAll();
    }
}
