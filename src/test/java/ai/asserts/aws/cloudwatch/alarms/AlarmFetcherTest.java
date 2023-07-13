/*
 *  Copyright © 2021.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.cloudwatch.alarms;

import ai.asserts.aws.AWSClientProvider;
import ai.asserts.aws.EnvironmentConfig;
import ai.asserts.aws.AWSApiCallRateLimiter;
import ai.asserts.aws.ScrapeConfigProvider;
import ai.asserts.aws.TaskExecutorUtil;
import ai.asserts.aws.TestTaskThreadPool;
import ai.asserts.aws.account.AWSAccount;
import ai.asserts.aws.account.AccountProvider;
import ai.asserts.aws.config.ScrapeConfig;
import ai.asserts.aws.exporter.AccountIDProvider;
import ai.asserts.aws.exporter.ECSServiceDiscoveryExporter;
import ai.asserts.aws.exporter.MetricSampleBuilder;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import io.prometheus.client.Collector;
import io.prometheus.client.CollectorRegistry;
import org.easymock.Capture;
import org.easymock.EasyMockSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.ComparisonOperator;
import software.amazon.awssdk.services.cloudwatch.model.DescribeAlarmsResponse;
import software.amazon.awssdk.services.cloudwatch.model.Dimension;
import software.amazon.awssdk.services.cloudwatch.model.MetricAlarm;

import java.time.Instant;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;

import static org.easymock.EasyMock.anyObject;
import static org.easymock.EasyMock.capture;
import static org.easymock.EasyMock.eq;
import static org.easymock.EasyMock.expect;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class AlarmFetcherTest extends EasyMockSupport {
    public CollectorRegistry collectorRegistry;
    private AccountProvider accountProvider;
    private AWSAccount awsAccount;
    private AWSApiCallRateLimiter rateLimiter;
    private AWSClientProvider awsClientProvider;
    private ScrapeConfigProvider scrapeConfigProvider;
    private ScrapeConfig scrapeConfig;
    private CloudWatchClient cloudWatchClient;
    private AccountIDProvider accountIDProvider;
    private AlarmMetricConverter alarmMetricConverter;
    private MetricSampleBuilder sampleBuilder;
    private ECSServiceDiscoveryExporter ecsServiceDiscoveryExporter;
    private EnvironmentConfig environmentConfig;
    private AlarmFetcher testClass;
    private Collector.MetricFamilySamples.Sample sample;
    private Collector.MetricFamilySamples familySamples;
    private Instant now;

    @BeforeEach
    public void setup() {
        now = Instant.now();
        awsAccount = new AWSAccount("tenant", "123456789", "", "", "", ImmutableSet.of("region"));
        accountProvider = mock(AccountProvider.class);
        scrapeConfigProvider = mock(ScrapeConfigProvider.class);
        scrapeConfig = mock(ScrapeConfig.class);
        awsClientProvider = mock(AWSClientProvider.class);
        rateLimiter = mock(AWSApiCallRateLimiter.class);
        sampleBuilder = mock(MetricSampleBuilder.class);
        collectorRegistry = mock(CollectorRegistry.class);
        cloudWatchClient = mock(CloudWatchClient.class);
        accountIDProvider = mock(AccountIDProvider.class);
        alarmMetricConverter = mock(AlarmMetricConverter.class);
        sample = mock(Collector.MetricFamilySamples.Sample.class);
        familySamples = mock(Collector.MetricFamilySamples.class);
        ecsServiceDiscoveryExporter = mock(ECSServiceDiscoveryExporter.class);
        environmentConfig = mock(EnvironmentConfig.class);
        testClass = new AlarmFetcher(accountProvider, awsClientProvider, collectorRegistry, rateLimiter,
                sampleBuilder, alarmMetricConverter, scrapeConfigProvider,
                ecsServiceDiscoveryExporter, new TaskExecutorUtil(new TestTaskThreadPool(), new AWSApiCallRateLimiter(null,
                (accountId) -> "tenant")), environmentConfig);
    }

    @Test
    @SuppressWarnings("unchecked")
    public void sendAlarmsForRegions_exposeAsMetric() {
        expect(environmentConfig.isSingleTenant()).andReturn(true);
        expect(environmentConfig.isSingleInstance()).andReturn(true);
        expect(ecsServiceDiscoveryExporter.isPrimaryExporter()).andReturn(true);
        expect(scrapeConfigProvider.getScrapeConfig("tenant")).andReturn(scrapeConfig).anyTimes();
        expect(scrapeConfig.isPullCWAlarms()).andReturn(true);
        expect(scrapeConfig.isCwAlarmAsMetric()).andReturn(true).anyTimes();
        expect(accountProvider.getAccounts()).andReturn(ImmutableSet.of(awsAccount));
        expect(accountIDProvider.getAccountId()).andReturn("123456789").anyTimes();
        expect(awsClientProvider.getCloudWatchClient("region", awsAccount))
                .andReturn(cloudWatchClient).anyTimes();

        Capture<AWSApiCallRateLimiter.AWSAPICall<DescribeAlarmsResponse>> callbackCapture = Capture.newInstance();

        MetricAlarm alarm = MetricAlarm.builder()
                .alarmName("alarm1")
                .stateValue("ALARM")
                .stateUpdatedTimestamp(now)
                .threshold(10.0)
                .comparisonOperator(ComparisonOperator.GREATER_THAN_THRESHOLD)
                .namespace("AWS/ECS")
                .dimensions(Dimension.builder()
                        .name("ServiceName")
                        .value("sample-ecs-service")
                        .build())
                .build();
        DescribeAlarmsResponse response = DescribeAlarmsResponse.builder()
                .metricAlarms(ImmutableList.of(alarm))
                .build();

        expect(alarmMetricConverter.extractMetricAndEntityLabels(alarm))
                .andReturn(ImmutableMap.of("label1", "value1"));

        expect(rateLimiter.doWithRateLimit(eq("CloudWatchClient/describeAlarms"),
                anyObject(SortedMap.class), capture(callbackCapture))).andReturn(response);
        SortedMap<String, String> labels = new TreeMap<>(new ImmutableMap.Builder<String, String>()
                .put("account_id", "123456789")
                .put("label1", "value1")
                .put("namespace", "AWS/ECS")
                .put("metric_namespace", "AWS/ECS")
                .put("metric_operator", ">")
                .put("region", "region")
                .put("state", "ALARM")
                .put("threshold", "10.0")
                .put("timestamp", now.toString())
                .put("workload", "sample-ecs-service")
                .put("d_ServiceName", "sample-ecs-service")
                .build());
        SortedMap<String, String> withoutTimestamp = new TreeMap<>(labels);
        withoutTimestamp.remove("timestamp");
        alarmMetricConverter.simplifyAlarmName(labels);
        expect(sampleBuilder.buildSingleSample("aws_cloudwatch_alarm", withoutTimestamp, 1.0D))
                .andReturn(Optional.of(sample));
        expect(sampleBuilder.buildFamily(ImmutableList.of(sample))).andReturn(Optional.of(familySamples));
        replayAll();
        testClass.update();
        assertEquals(ImmutableList.of(familySamples), testClass.collect());

        verifyAll();
    }

    @Test
    public void pullAlarm_disabled() {
        expect(environmentConfig.isSingleTenant()).andReturn(true);
        expect(environmentConfig.isSingleInstance()).andReturn(true);
        expect(ecsServiceDiscoveryExporter.isPrimaryExporter()).andReturn(true);
        expect(accountProvider.getAccounts()).andReturn(ImmutableSet.of(AWSAccount.builder()
                .regions(ImmutableSet.of("us-west-2"))
                .tenant("tenant")
                .build()));
        expect(scrapeConfigProvider.getScrapeConfig("tenant")).andReturn(scrapeConfig);
        expect(scrapeConfig.isPullCWAlarms()).andReturn(false);
        replayAll();
        testClass.update();
        assertEquals(ImmutableList.of(), testClass.collect());

        verifyAll();
    }

    @Test
    public void pullAlarm_notPrimaryExporter() {
        expect(environmentConfig.isSingleTenant()).andReturn(true);
        expect(environmentConfig.isSingleInstance()).andReturn(true);
        expect(ecsServiceDiscoveryExporter.isPrimaryExporter()).andReturn(false);
        replayAll();
        testClass.update();
        assertEquals(ImmutableList.of(), testClass.collect());

        verifyAll();
    }

}
