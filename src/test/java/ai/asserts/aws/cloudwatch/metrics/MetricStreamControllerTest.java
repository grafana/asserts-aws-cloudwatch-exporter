/*
 *  Copyright © 2022.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.cloudwatch.metrics;

import ai.asserts.aws.ApiAuthenticator;
import ai.asserts.aws.MetricNameUtil;
import ai.asserts.aws.ObjectMapperFactory;
import ai.asserts.aws.ScrapeConfigProvider;
import ai.asserts.aws.account.AccountTenantMapper;
import ai.asserts.aws.cloudwatch.alarms.FirehoseEventRequest;
import ai.asserts.aws.cloudwatch.alarms.RecordData;
import ai.asserts.aws.config.MetricConfig;
import ai.asserts.aws.config.NamespaceConfig;
import ai.asserts.aws.config.ScrapeConfig;
import ai.asserts.aws.exporter.BasicMetricCollector;
import ai.asserts.aws.model.CWNamespace;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import org.easymock.EasyMockSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;

import static ai.asserts.aws.model.MetricStat.SampleCount;
import static ai.asserts.aws.model.MetricStat.Sum;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.expectLastCall;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.http.HttpStatus.UNAUTHORIZED;

public class MetricStreamControllerTest extends EasyMockSupport {
    private CloudWatchMetrics metrics;
    private CloudWatchMetric metric1;
    private CloudWatchMetric metric2;
    private FirehoseEventRequest firehoseEventRequest;
    private RecordData recordData;
    private MetricStreamController testClass;
    private ObjectMapper objectMapper;
    private BasicMetricCollector metricCollector;
    private MetricNameUtil metricNameUtil;
    private ApiAuthenticator apiAuthenticator;
    private Instant now;
    private ScrapeConfigProvider scrapeConfigProvider;
    private ScrapeConfig scrapeConfig;

    private ObjectMapperFactory objectMapperFactory;
    private AccountTenantMapper accountTenantMapper;

    @BeforeEach
    public void setup() {
        firehoseEventRequest = mock(FirehoseEventRequest.class);
        metricCollector = mock(BasicMetricCollector.class);
        metricNameUtil = mock(MetricNameUtil.class);
        metrics = mock(CloudWatchMetrics.class);
        recordData = mock(RecordData.class);
        objectMapper = mock(ObjectMapper.class);
        apiAuthenticator = mock(ApiAuthenticator.class);
        scrapeConfigProvider = mock(ScrapeConfigProvider.class);
        scrapeConfig = mock(ScrapeConfig.class);
        objectMapperFactory = mock(ObjectMapperFactory.class);
        accountTenantMapper = mock(AccountTenantMapper.class);
        now = Instant.now();
        testClass = new MetricStreamController(objectMapperFactory, metricCollector, metricNameUtil, apiAuthenticator
                , scrapeConfigProvider, accountTenantMapper) {
            @Override
            Instant now() {
                return now;
            }
        };
        expect(accountTenantMapper.getTenantName("123")).andReturn("acme").anyTimes();
        expect(scrapeConfigProvider.getScrapeConfig("acme")).andReturn(scrapeConfig).anyTimes();
    }

    @Test
    public void receiveMetricsPost() throws JsonProcessingException {
        expectedCallsWhileProcessingData();
        expect(firehoseEventRequest.getRecords()).andReturn(ImmutableList.of(recordData)).times(2);
        expect(recordData.getData()).andReturn(Base64.getEncoder().encodeToString("test".getBytes()));
        expect(firehoseEventRequest.getRequestId()).andReturn("request-id");
        expect(objectMapper.readValue("{\"metrics\":[test]}", CloudWatchMetrics.class)).andReturn(metrics);
        expect(metrics.getMetrics()).andReturn(ImmutableList.of(metric1, metric2));
        replayAll();

        ResponseEntity<MetricResponse> metricResponseResponseEntity =
                testClass.receiveMetricsPost(firehoseEventRequest);
        MetricResponse body = metricResponseResponseEntity.getBody();
        assertEquals(HttpStatus.OK, metricResponseResponseEntity.getStatusCode());
        assertNotNull(body);
        assertEquals("request-id", body.getRequestId());
        assertNotNull(body.getTimestamp());
        verifyAll();
    }

    @Test
    public void receiveMetricsPut() throws JsonProcessingException {
        expectedCallsWhileProcessingData();

        expect(firehoseEventRequest.getRecords()).andReturn(ImmutableList.of(recordData)).times(2);
        expect(recordData.getData()).andReturn(Base64.getEncoder().encodeToString("test".getBytes()));
        expect(firehoseEventRequest.getRequestId()).andReturn("request-id");
        expect(objectMapper.readValue("{\"metrics\":[test]}", CloudWatchMetrics.class)).andReturn(metrics);
        expect(metrics.getMetrics()).andReturn(ImmutableList.of(metric1, metric2));
        replayAll();

        ResponseEntity<MetricResponse> metricResponseResponseEntity =
                testClass.receiveMetricsPut(firehoseEventRequest);
        MetricResponse body = metricResponseResponseEntity.getBody();
        assertEquals(HttpStatus.OK, metricResponseResponseEntity.getStatusCode());
        assertNotNull(body);
        assertEquals("request-id", body.getRequestId());
        assertNotNull(body.getTimestamp());

        verifyAll();
    }

    @Test
    public void receiveMetricsPost_InternalServerError() {
        testClass = new MetricStreamController(objectMapperFactory, metricCollector, metricNameUtil, apiAuthenticator
                , scrapeConfigProvider, accountTenantMapper) {
            @Override
            Instant now() {
                return now;
            }

            @Override
            void processRequest(FirehoseEventRequest firehoseEventRequest) {
                throw new RuntimeException("Error Message");
            }
        };
        expect(firehoseEventRequest.getRequestId()).andReturn("request-id");
        replayAll();

        ResponseEntity<MetricResponse> metricResponseResponseEntity =
                testClass.receiveMetricsPost(firehoseEventRequest);
        MetricResponse body = metricResponseResponseEntity.getBody();
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, metricResponseResponseEntity.getStatusCode());
        assertNotNull(body);
        assertEquals("request-id", body.getRequestId());
        assertEquals("Error Message", body.getErrorMessage());
        assertNotNull(body.getTimestamp());
        verifyAll();
    }

    @Test
    public void receiveMetricsPut_InternalServerError() {
        testClass = new MetricStreamController(objectMapperFactory, metricCollector, metricNameUtil, apiAuthenticator
                , scrapeConfigProvider, accountTenantMapper) {
            @Override
            Instant now() {
                return now;
            }

            @Override
            void processRequest(FirehoseEventRequest firehoseEventRequest) {
                throw new RuntimeException("Error Message");
            }
        };
        expect(firehoseEventRequest.getRequestId()).andReturn("request-id");
        replayAll();

        ResponseEntity<MetricResponse> metricResponseResponseEntity =
                testClass.receiveMetricsPut(firehoseEventRequest);
        MetricResponse body = metricResponseResponseEntity.getBody();
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, metricResponseResponseEntity.getStatusCode());
        assertNotNull(body);
        assertEquals("request-id", body.getRequestId());
        assertEquals("Error Message", body.getErrorMessage());
        assertNotNull(body.getTimestamp());

        verifyAll();
    }

    @Test
    public void receiveMetricsPostSecure() throws JsonProcessingException {
        expectedCallsWhileProcessingData();

        expect(firehoseEventRequest.getRecords()).andReturn(ImmutableList.of(recordData)).times(2);
        expect(recordData.getData()).andReturn(Base64.getEncoder().encodeToString("test".getBytes()));
        expect(firehoseEventRequest.getRequestId()).andReturn("request-id");
        expect(objectMapper.readValue("{\"metrics\":[test]}", CloudWatchMetrics.class)).andReturn(metrics);
        expect(metrics.getMetrics()).andReturn(ImmutableList.of(metric1, metric2));
        apiAuthenticator.authenticate(Optional.of("token"));
        replayAll();

        ResponseEntity<MetricResponse> metricResponseResponseEntity = testClass.receiveMetricsPostSecure("token",
                firehoseEventRequest);
        MetricResponse body = metricResponseResponseEntity.getBody();
        assertEquals(HttpStatus.OK, metricResponseResponseEntity.getStatusCode());
        assertNotNull(body);
        assertEquals("request-id", body.getRequestId());
        assertNotNull(body.getTimestamp());

        verifyAll();
    }

    @Test
    public void receiveMetricsPutSecure() throws JsonProcessingException {
        expectedCallsWhileProcessingData();

        expect(firehoseEventRequest.getRecords()).andReturn(ImmutableList.of(recordData)).times(2);
        expect(recordData.getData()).andReturn(Base64.getEncoder().encodeToString("test".getBytes()));
        expect(firehoseEventRequest.getRequestId()).andReturn("request-id");
        expect(objectMapper.readValue("{\"metrics\":[test]}", CloudWatchMetrics.class)).andReturn(metrics);
        expect(metrics.getMetrics()).andReturn(ImmutableList.of(metric1, metric2));
        apiAuthenticator.authenticate(Optional.of("token"));
        replayAll();

        ResponseEntity<MetricResponse> metricResponseResponseEntity = testClass.receiveMetricsPutSecure("token",
                firehoseEventRequest);
        MetricResponse body = metricResponseResponseEntity.getBody();
        assertEquals(HttpStatus.OK, metricResponseResponseEntity.getStatusCode());
        assertNotNull(body);
        assertEquals("request-id", body.getRequestId());
        assertNotNull(body.getTimestamp());

        verifyAll();
    }

    @Test
    public void receiveMetricsPostSecure_AuthFailure() {
        expect(firehoseEventRequest.getRequestId()).andReturn("request-id");
        apiAuthenticator.authenticate(Optional.of("token"));
        expectLastCall().andThrow(new RuntimeException());
        replayAll();

        ResponseEntity<MetricResponse> metricResponseResponseEntity = testClass.receiveMetricsPostSecure("token",
                firehoseEventRequest);
        MetricResponse body = metricResponseResponseEntity.getBody();
        assertEquals(UNAUTHORIZED, metricResponseResponseEntity.getStatusCode());
        assertNotNull(body);
        assertEquals("request-id", body.getRequestId());
        assertEquals("Authentication Failure", body.getErrorMessage());
        assertNotNull(body.getTimestamp());
        verifyAll();
    }

    @Test
    public void receiveMetricsPutSecure_AuthFailure() {
        expect(firehoseEventRequest.getRequestId()).andReturn("request-id");
        apiAuthenticator.authenticate(Optional.of("token"));
        expectLastCall().andThrow(new RuntimeException());
        replayAll();

        ResponseEntity<MetricResponse> metricResponseResponseEntity = testClass.receiveMetricsPutSecure("token",
                firehoseEventRequest);
        MetricResponse body = metricResponseResponseEntity.getBody();
        assertEquals(UNAUTHORIZED, metricResponseResponseEntity.getStatusCode());
        assertNotNull(body);
        assertEquals("request-id", body.getRequestId());
        assertEquals("Authentication Failure", body.getErrorMessage());
        assertNotNull(body.getTimestamp());
        verifyAll();
    }

    private void expectedCallsWhileProcessingData() {
        expect(objectMapperFactory.getObjectMapper()).andReturn(objectMapper);
        expect(scrapeConfigProvider.getStandardNamespace("AWS/Firehose"))
                .andReturn(Optional.of(CWNamespace.firehose))
                .anyTimes();
        expect(scrapeConfig.getNamespaces()).andReturn(ImmutableList.of(NamespaceConfig.builder()
                .name("AWS/Firehose")
                .metrics(ImmutableList.of(MetricConfig.builder()
                        .name("M1")
                        .stats(ImmutableSet.of(Sum, SampleCount))
                        .build()))
                .build())).anyTimes();
        expect(scrapeConfig.getMetricsToCapture()).andReturn(ImmutableMap.of(
                "aws_firehose_m1_sum", MetricConfig.builder().build(),
                "aws_firehose_m1_count", MetricConfig.builder().build())).anyTimes();
        metric1 = CloudWatchMetric.builder()
                .metric_name("M1")
                .namespace("AWS/Firehose")
                .region("r1")
                .account_id("123")
                .timestamp(now.plusSeconds(5).toEpochMilli())
                .dimensions(ImmutableMap.of("DeliveryStreamName", "PUT-HTP-SliCQ"))
                .value(new TreeMap<>(ImmutableMap.of("sum", 4.0f, "count", 2.0f, "average",
                        3.0f)))
                .build();

        // Excluded metric
        metric2 = CloudWatchMetric.builder()
                .metric_name("M2")
                .namespace("AWS/Firehose")
                .region("r1")
                .account_id("123")
                .timestamp(now.plusSeconds(5).toEpochMilli())
                .dimensions(ImmutableMap.of("DeliveryStreamName", "PUT-HTP-SliCQ"))
                .value(new TreeMap<>(ImmutableMap.of("sum", 4.0f, "count", 2.0f, "average",
                        3.0f)))
                .build();

        expect(metricNameUtil.toSnakeCase("aws_firehose_M1_sum")).andReturn("aws_firehose_m1_sum");
        expect(metricNameUtil.toSnakeCase("aws_firehose_M1_average")).andReturn("aws_firehose_m1_average").times(2);
        expect(metricNameUtil.toSnakeCase("aws_firehose_M1_count")).andReturn("aws_firehose_m1_count").times(2);
        expect(metricNameUtil.toSnakeCase("DeliveryStreamName")).andReturn("delivery_stream_name");

        expect(metricNameUtil.toSnakeCase("aws_firehose_M2_sum")).andReturn("aws_firehose_m2_sum");
        expect(metricNameUtil.toSnakeCase("aws_firehose_M2_average")).andReturn("aws_firehose_m2_average");
        expect(metricNameUtil.toSnakeCase("aws_firehose_M2_count")).andReturn("aws_firehose_m2_count");

        SortedMap<String, String> metricLabels = new TreeMap<>();
        metricLabels.put("tenant", "acme");
        metricLabels.put("d_delivery_stream_name", "PUT-HTP-SliCQ");
        metricLabels.put("account_id", "123");
        metricLabels.put("namespace", "AWS/Firehose");
        metricLabels.put("region", "r1");

        metricCollector.recordGaugeValue("aws_firehose_m1_sum", metricLabels, 4.0);
        metricCollector.recordGaugeValue("aws_firehose_m1_count", metricLabels, 2.0);
        expectLastCall().anyTimes();
    }
}
