/*
 *  Copyright © 2022.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.cloudwatch.alarms;

import org.easymock.EasyMockSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.easymock.EasyMock.expect;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class AlarmControllerTest extends EasyMockSupport {

    private AlarmMetricConverter alarmMetricConverter;
    private AlarmStateChange alarmStateChange;
    private AlarmController testClass;

    @BeforeEach
    public void setup() {
        alarmMetricConverter = mock(AlarmMetricConverter.class);
        alarmStateChange = mock(AlarmStateChange.class);
        testClass = new AlarmController(alarmMetricConverter);
    }

    @Test
    public void receiveAlarmsPost() {
        expect(alarmMetricConverter.convertAlarm(alarmStateChange)).andReturn(true);
        replayAll();

        assertEquals(HttpStatus.OK, testClass.receiveAlarmsPost(alarmStateChange).getStatusCode());

        verifyAll();
    }

    @Test
    public void receiveAlarmsPost_fail() {
        expect(alarmMetricConverter.convertAlarm(alarmStateChange)).andReturn(false);
        replayAll();

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, testClass.receiveAlarmsPost(alarmStateChange).getStatusCode());

        verifyAll();
    }

    @Test
    public void receiveAlarmsPut() {
        expect(alarmMetricConverter.convertAlarm(alarmStateChange)).andReturn(true);
        replayAll();

        assertEquals(HttpStatus.OK, testClass.receiveAlarmsPut(alarmStateChange).getStatusCode());

        verifyAll();
    }

    @Test
    public void receiveAlarmsPut_fail() {
        expect(alarmMetricConverter.convertAlarm(alarmStateChange)).andReturn(false);
        replayAll();

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, testClass.receiveAlarmsPut(alarmStateChange).getStatusCode());

        verifyAll();
    }
}
