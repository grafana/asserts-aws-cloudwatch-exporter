/*
 *  Copyright © 2021.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.cloudwatch.alarms;

import ai.asserts.aws.AssertsServerUtil;
import ai.asserts.aws.ScrapeConfigProvider;
import ai.asserts.aws.config.ScrapeConfig;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.easymock.Capture;
import org.easymock.EasyMockSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.SortedMap;
import java.util.TreeMap;

import static ai.asserts.aws.MetricNameUtil.TENANT;
import static org.easymock.EasyMock.capture;
import static org.easymock.EasyMock.eq;
import static org.easymock.EasyMock.expect;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.http.HttpMethod.POST;

public class AlertsProcessorTest extends EasyMockSupport {

    private ScrapeConfigProvider scrapeConfigProvider;
    private ScrapeConfig scrapeConfig;
    private RestTemplate restTemplate;
    private AlarmMetricExporter alarmMetricExporter;
    private AssertsServerUtil assertsServerUtil;
    private AlertsProcessor testClass;
    private Instant now;

    @BeforeEach
    public void setup() {
        now = Instant.now();
        scrapeConfigProvider = mock(ScrapeConfigProvider.class);
        scrapeConfig = mock(ScrapeConfig.class);
        restTemplate = mock(RestTemplate.class);
        alarmMetricExporter = mock(AlarmMetricExporter.class);
        assertsServerUtil = mock(AssertsServerUtil.class);
        testClass = new AlertsProcessor(scrapeConfigProvider, restTemplate, alarmMetricExporter,
                assertsServerUtil);
    }

    @Test
    public void sendAlerts() {
        SortedMap<String, String> labels1 = new TreeMap<>(new ImmutableMap.Builder<String, String>()
                .put("alertgroup", "cloudwatch")
                .put("asserts_alert_category", "error")
                .put("asserts_severity", "critical")
                .put("asserts_source", "cloudwatch.alarms")
                .put(TENANT, "tenant")
                .build());

        expect(scrapeConfigProvider.getScrapeConfig("tenant")).andReturn(scrapeConfig);
        expect(scrapeConfig.isCwAlarmAsMetric()).andReturn(false);
        expect(scrapeConfig.getTenant()).andReturn("tenant").anyTimes();

        HttpEntity<String> mockEntity = mock(HttpEntity.class);
        HttpHeaders mockHeaders = new HttpHeaders();
        expect(mockEntity.getHeaders()).andReturn(mockHeaders);
        expect(assertsServerUtil.getAlertForwardUrl()).andReturn("url");
        expect(assertsServerUtil.createAssertsAuthHeader()).andReturn(mockEntity);

        Capture<HttpEntity<PrometheusAlerts>> callbackCapture = Capture.newInstance();

        expect(restTemplate.exchange(eq("url?tenant=tenant"),
                eq(POST), capture(callbackCapture), eq(new ParameterizedTypeReference<String>() {
                })))
                .andReturn(ResponseEntity.ok("200"));
        replayAll();
        SortedMap<String, String> labels = new TreeMap<>(new ImmutableMap.Builder<String, String>()
                .put("state", "ALARM")
                .put(TENANT, "tenant")
                .put("timestamp", now.toString())
                .build());
        testClass.sendAlerts(ImmutableList.of(labels));
        assertEquals(mockHeaders, callbackCapture.getValue().getHeaders());
        PrometheusAlerts body = callbackCapture.getValue().getBody();
        assertNotNull(body);
        assertEquals(1, body.getAlerts().size());
        assertEquals(PrometheusAlertStatus.firing, body.getAlerts().get(0).getStatus());
        assertEquals(labels1, body.getAlerts().get(0).getLabels());
        verifyAll();
    }

    @Test
    public void exposeAsMetric() {
        SortedMap<String, String> labels = new TreeMap<>(new ImmutableMap.Builder<String, String>()
                .put("state", "ALARM")
                .put("tenant", "tenant")
                .put("timestamp", now.toString())
                .build());

        expect(scrapeConfigProvider.getScrapeConfig("tenant")).andReturn(scrapeConfig);
        expect(scrapeConfig.isCwAlarmAsMetric()).andReturn(true);

        alarmMetricExporter.processMetric(ImmutableList.of(labels));

        replayAll();

        testClass.sendAlerts(ImmutableList.of(labels));

        verifyAll();
    }
}
