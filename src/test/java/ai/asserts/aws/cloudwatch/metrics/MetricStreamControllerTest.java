/*
 *  Copyright © 2022.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.cloudwatch.metrics;

import ai.asserts.aws.ObjectMapperFactory;
import ai.asserts.aws.cloudwatch.alarms.FirehoseEventRequest;
import ai.asserts.aws.cloudwatch.alarms.RecordData;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.easymock.EasyMockSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import static org.easymock.EasyMock.expect;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class MetricStreamControllerTest extends EasyMockSupport {
    private CloudWatchMetricExporter exporter;
    private CloudWatchMetrics metrics;
    private CloudWatchMetric metric;
    private FirehoseEventRequest firehoseEventRequest;
    private RecordData recordData;
    private MetricStreamController testClass;
    private ObjectMapper objectMapper;
    private Map<String, String> labels;

    @BeforeEach
    public void setup() {
        exporter = mock(CloudWatchMetricExporter.class);
        firehoseEventRequest = mock(FirehoseEventRequest.class);
        metrics = mock(CloudWatchMetrics.class);
        recordData = mock(RecordData.class);
        objectMapper = mock(ObjectMapper.class);
        ObjectMapperFactory objectMapperFactory = mock(ObjectMapperFactory.class);
        testClass = new MetricStreamController(objectMapperFactory, exporter);
        expect(objectMapperFactory.getObjectMapper()).andReturn(objectMapper);
        metric = mock(CloudWatchMetric.class);
        expect(metric.getMetric_name()).andReturn("m1").times(2);
        expect(metric.getNamespace()).andReturn("n1").times(2);
        expect(metric.getRegion()).andReturn("r1");
        expect(metric.getAccount_id()).andReturn("123");
        expect(metric.getUnit()).andReturn("Percent");
        expect(metric.getDimensions()).andReturn(ImmutableMap.of("DeliveryStreamName", "PUT-HTP-SliCQ")).times(2);
        expect(metric.getValue()).andReturn(ImmutableMap.of("sum", 4.0f, "count", 2.0f)).times(4);
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
    public void receiveAlarmsPost() throws JsonProcessingException {
        expect(firehoseEventRequest.getRecords()).andReturn(ImmutableList.of(recordData)).times(2);
        expect(recordData.getData()).andReturn(Base64.getEncoder().encodeToString("test".getBytes()));
        expect(objectMapper.readValue("{\"metrics\":[test]}", CloudWatchMetrics.class)).andReturn(metrics);
        expect(metrics.getMetrics()).andReturn(ImmutableList.of(metric));
        exporter.addMetric(labels);
        replayAll();

        assertEquals(HttpStatus.OK, testClass.receiveMetricsPost(firehoseEventRequest).getStatusCode());

        verifyAll();
    }

    @Test
    public void receiveAlarmsPut() throws JsonProcessingException {
        expect(firehoseEventRequest.getRecords()).andReturn(ImmutableList.of(recordData)).times(2);
        expect(recordData.getData()).andReturn(Base64.getEncoder().encodeToString("test".getBytes()));
        expect(objectMapper.readValue("{\"metrics\":[test]}", CloudWatchMetrics.class)).andReturn(metrics);
        expect(metrics.getMetrics()).andReturn(ImmutableList.of(metric));
        exporter.addMetric(labels);
        replayAll();

        assertEquals(HttpStatus.OK, testClass.receiveMetricsPut(firehoseEventRequest).getStatusCode());

        verifyAll();
    }
}
