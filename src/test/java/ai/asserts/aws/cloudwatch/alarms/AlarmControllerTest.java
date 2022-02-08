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
    private AlarmStateChanged alarmStateChanged;
    private AlarmController testClass;

    @BeforeEach
    public void setup() {
        alarmMetricConverter = mock(AlarmMetricConverter.class);
        alarmStateChanged = mock(AlarmStateChanged.class);
        testClass = new AlarmController(alarmMetricConverter);
    }

    @Test
    public void receiveAlarmsPost() {
        expect(alarmMetricConverter.convertAlarm(alarmStateChanged)).andReturn(true);
        replayAll();

        assertEquals(HttpStatus.OK, testClass.receiveAlarmsPost(alarmStateChanged).getStatusCode());

        verifyAll();
    }

    @Test
    public void receiveAlarmsPost_fail() {
        expect(alarmMetricConverter.convertAlarm(alarmStateChanged)).andReturn(false);
        replayAll();

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, testClass.receiveAlarmsPost(alarmStateChanged).getStatusCode());

        verifyAll();
    }

    @Test
    public void receiveAlarmsPut() {
        expect(alarmMetricConverter.convertAlarm(alarmStateChanged)).andReturn(true);
        replayAll();

        assertEquals(HttpStatus.OK, testClass.receiveAlarmsPut(alarmStateChanged).getStatusCode());

        verifyAll();
    }

    @Test
    public void receiveAlarmsPut_fail() {
        expect(alarmMetricConverter.convertAlarm(alarmStateChanged)).andReturn(false);
        replayAll();

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, testClass.receiveAlarmsPut(alarmStateChanged).getStatusCode());

        verifyAll();
    }
}
