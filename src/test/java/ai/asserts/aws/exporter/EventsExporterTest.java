/*
 *  Copyright © 2021.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.exporter;

import ai.asserts.aws.AWSClientProvider;
import ai.asserts.aws.RateLimiter;
import ai.asserts.aws.cloudwatch.TimeWindowBuilder;
import ai.asserts.aws.cloudwatch.config.ScrapeConfig;
import ai.asserts.aws.cloudwatch.config.ScrapeConfigProvider;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import io.prometheus.client.Collector;
import org.easymock.Capture;
import org.easymock.EasyMockSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.cloudtrail.CloudTrailClient;
import software.amazon.awssdk.services.cloudtrail.model.Event;
import software.amazon.awssdk.services.cloudtrail.model.LookupAttribute;
import software.amazon.awssdk.services.cloudtrail.model.LookupAttributeKey;
import software.amazon.awssdk.services.cloudtrail.model.LookupEventsRequest;
import software.amazon.awssdk.services.cloudtrail.model.LookupEventsResponse;
import software.amazon.awssdk.services.cloudtrail.model.Resource;

import java.time.Instant;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.easymock.EasyMock.anyObject;
import static org.easymock.EasyMock.capture;
import static org.easymock.EasyMock.eq;
import static org.easymock.EasyMock.expect;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class EventsExporterTest extends EasyMockSupport {
    private AWSClientProvider awsClientProvider;
    private RateLimiter rateLimiter;
    private MetricSampleBuilder sampleBuilder;
    private TimeWindowBuilder timeWindowBuilder;
    private ScrapeConfigProvider scrapeConfigProvider;
    private Collector.MetricFamilySamples.Sample sample;
    private Collector.MetricFamilySamples metricFamilySamples;
    private EventsExporter testClass;
    private ScrapeConfig scrapeConfig;
    private CloudTrailClient cloudTrailClient;
    private Instant now;

    @BeforeEach
    public void setup() {
        now = Instant.now();
        awsClientProvider = mock(AWSClientProvider.class);
        rateLimiter = mock(RateLimiter.class);
        sampleBuilder = mock(MetricSampleBuilder.class);
        timeWindowBuilder = mock(TimeWindowBuilder.class);
        scrapeConfigProvider = mock(ScrapeConfigProvider.class);
        cloudTrailClient = mock(CloudTrailClient.class);
        sample = mock(Collector.MetricFamilySamples.Sample.class);
        metricFamilySamples = mock(Collector.MetricFamilySamples.class);
        testClass = new EventsExporter(scrapeConfigProvider, awsClientProvider, rateLimiter, sampleBuilder,
                timeWindowBuilder);
        scrapeConfig = mock(ScrapeConfig.class);
        expect(scrapeConfigProvider.getScrapeConfig()).andReturn(scrapeConfig);
        expect(scrapeConfig.getDelay()).andReturn(0);
        expect(scrapeConfig.getScrapeInterval()).andReturn(60);
        expect(scrapeConfig.isImportEvents()).andReturn(true);
        expect(scrapeConfig.getRegions()).andReturn(ImmutableSet.of("region"));
    }

    @Test
    public void update() {
        SortedSet<String> resourceType = new TreeSet<>();
        resourceType.add("AWS::S3::Bucket");
        expect(scrapeConfig.getDiscoverResourceTypes()).andReturn(resourceType);
        expect(awsClientProvider.getCloudTrailClient("region")).andReturn(cloudTrailClient).anyTimes();
        Capture<RateLimiter.AWSAPICall<LookupEventsResponse>> callbackCapture = Capture.newInstance();
        Event event1 = Event.builder().eventName("event1").eventSource("aws").resources(Resource.builder()
                .resourceType("AWS::S3::Bucket")
                .resourceName("bucket1")
                .build()).build();
        LookupEventsResponse response = LookupEventsResponse.builder()
                .nextToken(null)
                .events(ImmutableList.of(event1))
                .build();
        Instant endTime = now.minusSeconds(0);
        Instant startTime = now.minusSeconds(60);
        expect(timeWindowBuilder.getTimePeriod("region", 60)).andReturn(new Instant[]{
                now.minusSeconds(60), now}).times(2);
        LookupEventsRequest request = LookupEventsRequest.builder()
                .startTime(startTime)
                .endTime(endTime)
                .maxResults(20)
                .nextToken(null)
                .lookupAttributes(LookupAttribute.builder()
                        .attributeKey(LookupAttributeKey.RESOURCE_TYPE)
                        .attributeValue("AWS::S3::Bucket")
                        .build())
                .build();
        expect(cloudTrailClient.lookupEvents(request)).andReturn(response);
        expect(rateLimiter.doWithRateLimit(eq("CloudTrailClient/lookupEvents"),
                anyObject(SortedMap.class), capture(callbackCapture))).andReturn(response).times(2);
        SortedMap<String, String> labels = new TreeMap<>();
        labels.put("alertname", "event1");
        labels.put("alertstate", "firing");
        labels.put("alertgroup", "aws_exporter");
        labels.put("asserts_alert_category", "amend");
        labels.put("asserts_severity", "info");
        labels.put("asserts_source", "aws");
        labels.put("service", "bucket1");
        labels.put("job", "bucket1");
        labels.put("asserts_entity_type", "Service");
        labels.put("namespace", "AWS/S3");
        labels.put("region", "region");
        expect(sampleBuilder.buildSingleSample("ALERTS", labels, 1.0D)).andReturn(sample);
        expect(sampleBuilder.buildFamily(ImmutableList.of(sample))).andReturn(metricFamilySamples);
        cloudTrailClient.close();

        replayAll();
        testClass.update();

        assertEquals(response, callbackCapture.getValue().makeCall());

        assertEquals(ImmutableList.of(metricFamilySamples), testClass.collect());
        verifyAll();
    }

    @Test
    public void alarm() {
        expect(scrapeConfig.getDiscoverResourceTypes()).andReturn(new TreeSet<>());
        expect(awsClientProvider.getCloudTrailClient("region")).andReturn(cloudTrailClient).anyTimes();
        Capture<RateLimiter.AWSAPICall<LookupEventsResponse>> callbackCapture = Capture.newInstance();
        Event event1 = Event.builder()
                .eventName("PutMetricAlarm")
                .eventSource("aws")
                .cloudTrailEvent("{\n" +
                        "   \"requestParameters\":{\n" +
                        "      \"namespace\":\"AWS/S3\",\n" +
                        "      \"alarmName\":\"alarm1\"\n" +
                        "   }\n" +
                        "}")
                .resources(Resource.builder()
                        .resourceType("AWS::CloudWatch::Alarm")
                        .resourceName("bucket1")
                        .build())
                .build();
        LookupEventsResponse response = LookupEventsResponse.builder()
                .nextToken(null)
                .events(ImmutableList.of(event1))
                .build();
        Instant endTime = now.minusSeconds(0);
        Instant startTime = now.minusSeconds(60);
        expect(timeWindowBuilder.getTimePeriod("region", 60)).andReturn(new Instant[]{
                now.minusSeconds(60), now});
        LookupEventsRequest request = LookupEventsRequest.builder()
                .startTime(startTime)
                .endTime(endTime)
                .maxResults(20)
                .nextToken(null)
                .lookupAttributes(LookupAttribute.builder()
                        .attributeKey(LookupAttributeKey.RESOURCE_TYPE)
                        .attributeValue("AWS::CloudWatch::Alarm")
                        .build())
                .build();
        expect(cloudTrailClient.lookupEvents(request)).andReturn(response);
        expect(rateLimiter.doWithRateLimit(eq("CloudTrailClient/lookupEvents"),
                anyObject(SortedMap.class), capture(callbackCapture))).andReturn(response);
        SortedMap<String, String> labels = new TreeMap<>();
        labels.put("alertname", "alarm1");
        labels.put("alertstate", "firing");
        labels.put("alertgroup", "aws_exporter");
        labels.put("asserts_alert_category", "error");
        labels.put("asserts_severity", "warning");
        labels.put("asserts_source", "aws");
        labels.put("service", "bucket1");
        labels.put("job", "bucket1");
        labels.put("asserts_entity_type", "Service");
        labels.put("namespace", "AWS/S3");
        labels.put("region", "region");
        expect(sampleBuilder.buildSingleSample("ALERTS", labels, 1.0D)).andReturn(sample);
        expect(sampleBuilder.buildFamily(ImmutableList.of(sample))).andReturn(metricFamilySamples);
        cloudTrailClient.close();

        replayAll();
        testClass.update();

        assertEquals(response, callbackCapture.getValue().makeCall());

        assertEquals(ImmutableList.of(metricFamilySamples), testClass.collect());
        verifyAll();
    }
}
