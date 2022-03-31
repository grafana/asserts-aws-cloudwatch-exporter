/*
 *  Copyright © 2022.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.cloudwatch.alarms;

import ai.asserts.aws.ApiAuthenticator;
import ai.asserts.aws.ObjectMapperFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.easymock.EasyMockSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.Base64;
import java.util.Optional;

import static org.easymock.EasyMock.expect;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class AlarmControllerTest extends EasyMockSupport {

    private AlarmMetricConverter alarmMetricConverter;
    private FirehoseEventRequest firehoseEventRequest;
    private RecordData recordData;
    private AlarmStateChange alarmStateChange;
    private ObjectMapper objectMapper;
    private AlertsProcessor alertsProcessor;
    private ApiAuthenticator apiAuthenticator;
    private AlarmController testClass;

    @BeforeEach
    public void setup() {
        alarmMetricConverter = mock(AlarmMetricConverter.class);
        alarmStateChange = mock(AlarmStateChange.class);
        ObjectMapperFactory objectMapperFactory = mock(ObjectMapperFactory.class);
        objectMapper = mock(ObjectMapper.class);
        firehoseEventRequest = mock(FirehoseEventRequest.class);
        recordData = mock(RecordData.class);
        alertsProcessor = mock(AlertsProcessor.class);
        apiAuthenticator = mock(ApiAuthenticator.class);
        testClass = new AlarmController(alarmMetricConverter, objectMapperFactory, alertsProcessor, apiAuthenticator);
        expect(objectMapperFactory.getObjectMapper()).andReturn(objectMapper);
    }

    @Test
    public void receiveAlarmsPost() throws JsonProcessingException {
        expect(firehoseEventRequest.getRecords()).andReturn(ImmutableList.of(recordData)).times(2);
        expect(recordData.getData()).andReturn(Base64.getEncoder().encodeToString("test".getBytes()));
        expect(objectMapper.readValue("test", AlarmStateChange.class)).andReturn(alarmStateChange);
        expect(alarmMetricConverter.convertAlarm(alarmStateChange)).andReturn(ImmutableList.of(
                ImmutableMap.of("state", "ALARM")));
        alertsProcessor.sendAlerts(ImmutableList.of(ImmutableMap.of("state", "ALARM")));
        apiAuthenticator.authenticate(Optional.empty());
        replayAll();

        assertEquals(HttpStatus.OK, testClass.receiveAlarmsPost(firehoseEventRequest).getStatusCode());

        verifyAll();
    }

    @Test
    public void receiveAlarmsPostSecure() throws JsonProcessingException {
        expect(firehoseEventRequest.getRecords()).andReturn(ImmutableList.of(recordData)).times(2);
        expect(recordData.getData()).andReturn(Base64.getEncoder().encodeToString("test".getBytes()));
        expect(objectMapper.readValue("test", AlarmStateChange.class)).andReturn(alarmStateChange);
        expect(alarmMetricConverter.convertAlarm(alarmStateChange)).andReturn(ImmutableList.of(
                ImmutableMap.of("state", "ALARM")));
        alertsProcessor.sendAlerts(ImmutableList.of(ImmutableMap.of("state", "ALARM")));
        apiAuthenticator.authenticate(Optional.of("token"));
        replayAll();

        assertEquals(HttpStatus.OK, testClass.receiveAlarmsPostSecure("token",
                firehoseEventRequest).getStatusCode());

        verifyAll();
    }

    @Test
    public void receiveAlarmsPost_fail() throws JsonProcessingException {
        expect(firehoseEventRequest.getRecords()).andReturn(ImmutableList.of(recordData)).times(2);
        expect(recordData.getData()).andReturn(Base64.getEncoder().encodeToString("test".getBytes()));
        expect(objectMapper.readValue("test", AlarmStateChange.class)).andReturn(alarmStateChange);
        expect(alarmMetricConverter.convertAlarm(alarmStateChange)).andReturn(ImmutableList.of());
        expect(alarmStateChange.getResources()).andReturn(ImmutableList.of("resource1"));
        apiAuthenticator.authenticate(Optional.empty());
        replayAll();

        assertEquals(HttpStatus.OK, testClass.receiveAlarmsPost(firehoseEventRequest).getStatusCode());

        verifyAll();
    }

    @Test
    public void receiveAlarmsPut() throws JsonProcessingException {
        expect(firehoseEventRequest.getRecords()).andReturn(ImmutableList.of(recordData)).times(2);
        expect(recordData.getData()).andReturn(Base64.getEncoder().encodeToString("test".getBytes()));
        expect(objectMapper.readValue("test", AlarmStateChange.class)).andReturn(alarmStateChange);
        expect(alarmMetricConverter.convertAlarm(alarmStateChange)).andReturn(ImmutableList.of(
                ImmutableMap.of("state", "ALARM")));
        alertsProcessor.sendAlerts(ImmutableList.of(ImmutableMap.of("state", "ALARM")));
        apiAuthenticator.authenticate(Optional.empty());
        replayAll();

        assertEquals(HttpStatus.OK, testClass.receiveAlarmsPut(firehoseEventRequest).getStatusCode());

        verifyAll();
    }

    @Test
    public void receiveAlarmsPutSecure() throws JsonProcessingException {
        expect(firehoseEventRequest.getRecords()).andReturn(ImmutableList.of(recordData)).times(2);
        expect(recordData.getData()).andReturn(Base64.getEncoder().encodeToString("test".getBytes()));
        expect(objectMapper.readValue("test", AlarmStateChange.class)).andReturn(alarmStateChange);
        expect(alarmMetricConverter.convertAlarm(alarmStateChange)).andReturn(ImmutableList.of(
                ImmutableMap.of("state", "ALARM")));
        alertsProcessor.sendAlerts(ImmutableList.of(ImmutableMap.of("state", "ALARM")));
        apiAuthenticator.authenticate(Optional.of("token"));
        replayAll();

        assertEquals(HttpStatus.OK, testClass.receiveAlarmsPutSecure(
                "token",
                firehoseEventRequest).getStatusCode());

        verifyAll();
    }

    @Test
    public void receiveAlarmsPut_fail() throws JsonProcessingException {
        expect(firehoseEventRequest.getRecords()).andReturn(ImmutableList.of(recordData)).times(2);
        expect(recordData.getData()).andReturn(Base64.getEncoder().encodeToString("test".getBytes()));
        expect(objectMapper.readValue("test", AlarmStateChange.class)).andReturn(alarmStateChange);
        expect(alarmMetricConverter.convertAlarm(alarmStateChange)).andReturn(ImmutableList.of());
        expect(alarmStateChange.getResources()).andReturn(ImmutableList.of("resource1"));
        apiAuthenticator.authenticate(Optional.empty());
        replayAll();

        assertEquals(HttpStatus.OK, testClass.receiveAlarmsPut(firehoseEventRequest).getStatusCode());

        verifyAll();
    }
}
