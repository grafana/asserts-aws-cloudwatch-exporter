/*
 *  Copyright © 2022.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.cloudwatch.metrics;

import ai.asserts.aws.ApiAuthenticator;
import ai.asserts.aws.MetricNameUtil;
import ai.asserts.aws.ObjectMapperFactory;
import ai.asserts.aws.cloudwatch.alarms.FirehoseEventRequest;
import ai.asserts.aws.cloudwatch.alarms.RecordData;
import ai.asserts.aws.exporter.BasicMetricCollector;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.easymock.EasyMockSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;

import static org.easymock.EasyMock.expect;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class MetricStreamControllerTest extends EasyMockSupport {
    private CloudWatchMetrics metrics;
    private CloudWatchMetric metric;
    private FirehoseEventRequest firehoseEventRequest;
    private RecordData recordData;
    private MetricStreamController testClass;
    private ObjectMapper objectMapper;
    private BasicMetricCollector metricCollector;
    private MetricNameUtil metricNameUtil;
    private Map<String, String> labels;
    private ApiAuthenticator apiAuthenticator;
    private Instant now;

    @BeforeEach
    public void setup() {
        firehoseEventRequest = mock(FirehoseEventRequest.class);
        metricCollector = mock(BasicMetricCollector.class);
        metricNameUtil = mock(MetricNameUtil.class);
        metrics = mock(CloudWatchMetrics.class);
        recordData = mock(RecordData.class);
        objectMapper = mock(ObjectMapper.class);
        apiAuthenticator = mock(ApiAuthenticator.class);
        ObjectMapperFactory objectMapperFactory = mock(ObjectMapperFactory.class);
        now = Instant.now();
        testClass = new MetricStreamController(objectMapperFactory, metricCollector, metricNameUtil, apiAuthenticator) {
            @Override
            Instant now() {
                return now;
            }
        };
        expect(objectMapperFactory.getObjectMapper()).andReturn(objectMapper);
        metric = mock(CloudWatchMetric.class);
        expect(metric.getMetric_name()).andReturn("m1").times(2);
        expect(metric.getNamespace()).andReturn("AWS/Firehose").times(2);
        expect(metric.getRegion()).andReturn("r1");
        expect(metric.getAccount_id()).andReturn("123");
        expect(metric.getTimestamp()).andReturn(now.plusSeconds(5).toEpochMilli());
        expect(metric.getDimensions()).andReturn(ImmutableMap.of("DeliveryStreamName", "PUT-HTP-SliCQ")).times(2);
        expect(metric.getValue()).andReturn(ImmutableMap.of("sum", 4.0f, "count", 2.0f));
        expect(metricNameUtil.toSnakeCase("aws_firehose_m1_sum")).andReturn("aws_firehose_m1_sum");
        expect(metricNameUtil.toSnakeCase("aws_firehose_m1_samples")).andReturn("aws_firehose_m1_samples");
        expect(metricNameUtil.toSnakeCase("DeliveryStreamName")).andReturn("delivery_stream_name");

        SortedMap<String, String> metricLabels = new TreeMap<>();
        metricLabels.put("delivery_stream_name", "PUT-HTP-SliCQ");
        metricLabels.put("account_id", "123");
        metricLabels.put("namespace", "AWS/Firehose");
        metricLabels.put("region", "r1");

        SortedMap<String, String> metricHistoLabels = new TreeMap<>();
        metricHistoLabels.put("namespace", "AWS/Firehose");
        metricHistoLabels.put("region", "r1");
        metricHistoLabels.put("metric_name", "aws_firehose_m1");

        metricCollector.recordGaugeValue("aws_firehose_m1_sum", metricLabels, 4.0);
        metricCollector.recordGaugeValue("aws_firehose_m1_samples", metricLabels, 2.0);
        metricCollector.recordHistogram(MetricNameUtil.EXPORTER_DELAY_SECONDS, metricHistoLabels, -5);
        labels = new HashMap<>();
        labels.put("unit", "Percent");
        labels.put("account_id", "123");
        labels.put("metric_name", "m1");
        labels.put("namespace", "n1");
        labels.put("DeliveryStreamName", "PUT-HTP-SliCQ");
        labels.put("region", "r1");
        labels.put("value", "2.0");

    }

    @Test
    public void receiveMetricsPost() throws JsonProcessingException {
        expect(firehoseEventRequest.getRecords()).andReturn(ImmutableList.of(recordData)).times(2);
        expect(recordData.getData()).andReturn(Base64.getEncoder().encodeToString("test".getBytes()));
        expect(objectMapper.readValue("{\"metrics\":[test]}", CloudWatchMetrics.class)).andReturn(metrics);
        expect(metrics.getMetrics()).andReturn(ImmutableList.of(metric));
        apiAuthenticator.authenticate(Optional.empty());
        replayAll();

        assertEquals(HttpStatus.OK, testClass.receiveMetricsPost(firehoseEventRequest).getStatusCode());

        verifyAll();
    }

    @Test
    public void receiveMetricsPut() throws JsonProcessingException {
        expect(firehoseEventRequest.getRecords()).andReturn(ImmutableList.of(recordData)).times(2);
        expect(recordData.getData()).andReturn(Base64.getEncoder().encodeToString("test".getBytes()));
        expect(objectMapper.readValue("{\"metrics\":[test]}", CloudWatchMetrics.class)).andReturn(metrics);
        expect(metrics.getMetrics()).andReturn(ImmutableList.of(metric));
        apiAuthenticator.authenticate(Optional.empty());
        replayAll();

        assertEquals(HttpStatus.OK, testClass.receiveMetricsPut(firehoseEventRequest).getStatusCode());

        verifyAll();
    }

    @Test
    public void receiveMetricsPostSecure() throws JsonProcessingException {
        expect(firehoseEventRequest.getRecords()).andReturn(ImmutableList.of(recordData)).times(2);
        expect(recordData.getData()).andReturn(Base64.getEncoder().encodeToString("test".getBytes()));
        expect(objectMapper.readValue("{\"metrics\":[test]}", CloudWatchMetrics.class)).andReturn(metrics);
        expect(metrics.getMetrics()).andReturn(ImmutableList.of(metric));
        apiAuthenticator.authenticate(Optional.of("token"));
        replayAll();

        assertEquals(HttpStatus.OK, testClass.receiveMetricsPostSecure("token",
                firehoseEventRequest).getStatusCode());

        verifyAll();
    }

    @Test
    public void receiveMetricsPutSecure() throws JsonProcessingException {
        expect(firehoseEventRequest.getRecords()).andReturn(ImmutableList.of(recordData)).times(2);
        expect(recordData.getData()).andReturn(Base64.getEncoder().encodeToString("test".getBytes()));
        expect(objectMapper.readValue("{\"metrics\":[test]}", CloudWatchMetrics.class)).andReturn(metrics);
        expect(metrics.getMetrics()).andReturn(ImmutableList.of(metric));
        apiAuthenticator.authenticate(Optional.of("token"));
        replayAll();

        assertEquals(HttpStatus.OK, testClass.receiveMetricsPutSecure("token",
                firehoseEventRequest).getStatusCode());

        verifyAll();
    }
}
