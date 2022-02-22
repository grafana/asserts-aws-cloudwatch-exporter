/*
 *  Copyright © 2022.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.cloudwatch.alarms;

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

import static org.easymock.EasyMock.expect;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class AlarmControllerTest extends EasyMockSupport {

    private AlarmMetricConverter alarmMetricConverter;
    private AlarmRequest alarmRequest;
    private AlarmRecord alarmRecord;
    private AlarmStateChange alarmStateChange;
    private ObjectMapper objectMapper;
    private AlertsProcessor alertsProcessor;
    private AlarmController testClass;

    @BeforeEach
    public void setup() {
        alarmMetricConverter = mock(AlarmMetricConverter.class);
        alarmStateChange = mock(AlarmStateChange.class);
        ObjectMapperFactory objectMapperFactory = mock(ObjectMapperFactory.class);
        objectMapper = mock(ObjectMapper.class);
        alarmRequest = mock(AlarmRequest.class);
        alarmRecord = mock(AlarmRecord.class);
        alertsProcessor = mock(AlertsProcessor.class);
        testClass = new AlarmController(alarmMetricConverter, objectMapperFactory, alertsProcessor);
        expect(objectMapperFactory.getObjectMapper()).andReturn(objectMapper);
    }

    @Test
    public void receiveAlarmsPost() throws JsonProcessingException {
        expect(alarmRequest.getRecords()).andReturn(ImmutableList.of(alarmRecord)).times(2);
        expect(alarmRecord.getData()).andReturn(Base64.getEncoder().encodeToString("test".getBytes()));
        expect(objectMapper.readValue("test", AlarmStateChange.class)).andReturn(alarmStateChange);
        expect(alarmMetricConverter.convertAlarm(alarmStateChange)).andReturn(ImmutableList.of(
                ImmutableMap.of("state", "ALARM")));
        alertsProcessor.sendAlerts(ImmutableList.of(ImmutableMap.of("state", "ALARM")));
        replayAll();

        assertEquals(HttpStatus.OK, testClass.receiveAlarmsPost(alarmRequest).getStatusCode());

        verifyAll();
    }

    @Test
    public void receiveAlarmsPost_fail() throws JsonProcessingException {
        expect(alarmRequest.getRecords()).andReturn(ImmutableList.of(alarmRecord)).times(2);
        expect(alarmRecord.getData()).andReturn(Base64.getEncoder().encodeToString("test".getBytes()));
        expect(objectMapper.readValue("test", AlarmStateChange.class)).andReturn(alarmStateChange);
        expect(alarmMetricConverter.convertAlarm(alarmStateChange)).andReturn(ImmutableList.of());
        expect(alarmStateChange.getResources()).andReturn(ImmutableList.of("resource1"));
        replayAll();

        assertEquals(HttpStatus.OK, testClass.receiveAlarmsPost(alarmRequest).getStatusCode());

        verifyAll();
    }

    @Test
    public void receiveAlarmsPut() throws JsonProcessingException {
        expect(alarmRequest.getRecords()).andReturn(ImmutableList.of(alarmRecord)).times(2);
        expect(alarmRecord.getData()).andReturn(Base64.getEncoder().encodeToString("test".getBytes()));
        expect(objectMapper.readValue("test", AlarmStateChange.class)).andReturn(alarmStateChange);
        expect(alarmMetricConverter.convertAlarm(alarmStateChange)).andReturn(ImmutableList.of(
                ImmutableMap.of("state", "ALARM")));
        alertsProcessor.sendAlerts(ImmutableList.of(ImmutableMap.of("state", "ALARM")));
        replayAll();

        assertEquals(HttpStatus.OK, testClass.receiveAlarmsPut(alarmRequest).getStatusCode());

        verifyAll();
    }

    @Test
    public void receiveAlarmsPut_fail() throws JsonProcessingException {
        expect(alarmRequest.getRecords()).andReturn(ImmutableList.of(alarmRecord)).times(2);
        expect(alarmRecord.getData()).andReturn(Base64.getEncoder().encodeToString("test".getBytes()));
        expect(objectMapper.readValue("test", AlarmStateChange.class)).andReturn(alarmStateChange);
        expect(alarmMetricConverter.convertAlarm(alarmStateChange)).andReturn(ImmutableList.of());
        expect(alarmStateChange.getResources()).andReturn(ImmutableList.of("resource1"));
        replayAll();

        assertEquals(HttpStatus.OK, testClass.receiveAlarmsPut(alarmRequest).getStatusCode());

        verifyAll();
    }
}
