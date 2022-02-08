/*
 *  Copyright © 2022.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.cloudwatch.alarms;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.easymock.EasyMockSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.easymock.EasyMock.expect;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class AlarmControllerTest extends EasyMockSupport {

    private AlarmMetricConverter alarmMetricConverter;
    private AlarmMetricExporter alarmMetricExporter;
    private AlarmStateChange alarmStateChange;
    private AlarmController testClass;

    @BeforeEach
    public void setup() {
        alarmMetricConverter = mock(AlarmMetricConverter.class);
        alarmMetricExporter = mock(AlarmMetricExporter.class);
        alarmStateChange = mock(AlarmStateChange.class);
        testClass = new AlarmController(alarmMetricConverter, alarmMetricExporter);
    }

    @Test
    public void receiveAlarmsPost() {
        expect(alarmMetricConverter.convertAlarm(alarmStateChange)).andReturn(ImmutableList.of(
                ImmutableMap.of("state", "ALARM")));
        alarmMetricExporter.processMetric(ImmutableList.of(ImmutableMap.of("state", "ALARM")));
        replayAll();

        assertEquals(HttpStatus.OK, testClass.receiveAlarmsPost(alarmStateChange).getStatusCode());

        verifyAll();
    }

    @Test
    public void receiveAlarmsPost_fail() {
        expect(alarmMetricConverter.convertAlarm(alarmStateChange)).andReturn(ImmutableList.of());
        replayAll();

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, testClass.receiveAlarmsPost(alarmStateChange).getStatusCode());

        verifyAll();
    }

    @Test
    public void receiveAlarmsPut() {
        expect(alarmMetricConverter.convertAlarm(alarmStateChange)).andReturn(ImmutableList.of(
                ImmutableMap.of("state", "ALARM")));
        alarmMetricExporter.processMetric(ImmutableList.of(ImmutableMap.of("state", "ALARM")));
        replayAll();

        assertEquals(HttpStatus.OK, testClass.receiveAlarmsPut(alarmStateChange).getStatusCode());

        verifyAll();
    }

    @Test
    public void receiveAlarmsPut_fail() {
        expect(alarmMetricConverter.convertAlarm(alarmStateChange)).andReturn(ImmutableList.of());
        replayAll();

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, testClass.receiveAlarmsPut(alarmStateChange).getStatusCode());

        verifyAll();
    }
}
