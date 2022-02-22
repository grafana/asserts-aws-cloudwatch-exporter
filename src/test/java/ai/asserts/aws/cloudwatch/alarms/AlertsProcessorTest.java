/*
 *  Copyright © 2021.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.cloudwatch.alarms;

import ai.asserts.aws.cloudwatch.config.ScrapeConfig;
import ai.asserts.aws.cloudwatch.config.ScrapeConfigProvider;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.easymock.Capture;
import org.easymock.EasyMockSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.SortedMap;
import java.util.TreeMap;

import static org.easymock.EasyMock.capture;
import static org.easymock.EasyMock.eq;
import static org.easymock.EasyMock.expect;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class AlertsProcessorTest extends EasyMockSupport {

    private ScrapeConfigProvider scrapeConfigProvider;
    private ScrapeConfig scrapeConfig;
    private RestTemplate restTemplate;
    private AlertsProcessor testClass;
    private AlarmMetricExporter alarmMetricExporter;
    private Instant now;

    @BeforeEach
    public void setup() {
        now = Instant.now();
        scrapeConfigProvider = mock(ScrapeConfigProvider.class);
        scrapeConfig = mock(ScrapeConfig.class);
        restTemplate = mock(RestTemplate.class);
        alarmMetricExporter = mock(AlarmMetricExporter.class);
        testClass = new AlertsProcessor(scrapeConfigProvider, restTemplate, alarmMetricExporter);
    }

    @Test
    public void sendAlerts() {
        SortedMap<String, String> labels1 = new TreeMap<>(new ImmutableMap.Builder<String, String>()
                .put("alertgroup", "cloudwatch")
                .put("asserts_alert_category", "error")
                .put("asserts_severity", "critical")
                .put("asserts_source", "cloudwatch.alarms")
                .build());
        expect(scrapeConfigProvider.getScrapeConfig()).andReturn(scrapeConfig).times(2);
        expect(scrapeConfig.getAlertForwardUrl()).andReturn("http://localhost:8040/assertion-detector/v4/prometheus-alerts");
        expect(scrapeConfig.getTenant()).andReturn("tenant");
        Capture<HttpEntity<PrometheusAlerts>> callbackCapture = Capture.newInstance();
        Capture<String> callbackCapture1 = Capture.newInstance();
        String url = "url1?tenant=tenant";
        expect(restTemplate.postForObject(capture(callbackCapture1), capture(callbackCapture), eq(Object.class))).andReturn(new String());
        replayAll();
        SortedMap<String, String> labels = new TreeMap<>(new ImmutableMap.Builder<String, String>()
                .put("state", "ALARM")
                .put("timestamp", now.toString())
                .build());
        testClass.sendAlerts(ImmutableList.of(labels));
        assertEquals(1, callbackCapture.getValue().getBody().getAlerts().size());
        assertEquals(PrometheusAlertStatus.firing, callbackCapture.getValue().getBody().getAlerts().get(0).getStatus());
        assertEquals(labels1, callbackCapture.getValue().getBody().getAlerts().get(0).getLabels());
        verifyAll();
    }

    @Test
    public void sendAlerts_metrics() {
        SortedMap<String, String> labels1 = new TreeMap<>(new ImmutableMap.Builder<String, String>()
                .put("alertgroup", "cloudwatch")
                .put("asserts_alert_category", "error")
                .put("asserts_severity", "critical")
                .put("asserts_source", "cloudwatch.alarms")
                .build());
        SortedMap<String, String> labels = new TreeMap<>(new ImmutableMap.Builder<String, String>()
                .put("state", "ALARM")
                .put("timestamp", now.toString())
                .build());
        expect(scrapeConfigProvider.getScrapeConfig()).andReturn(scrapeConfig).times(2);
        expect(scrapeConfig.getAlertForwardUrl()).andReturn("");
        expect(scrapeConfig.getTenant()).andReturn("tenant");
        alarmMetricExporter.processMetric(ImmutableList.of(labels));
        replayAll();
        testClass.sendAlerts(ImmutableList.of(labels));
        verifyAll();
    }
}
