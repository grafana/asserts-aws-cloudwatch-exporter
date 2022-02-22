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

import java.util.List;
import java.util.Map;

import static org.easymock.EasyMock.expect;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class AlarmMetricConverterTest extends EasyMockSupport {

    private AlarmMetricConverter testClass;
    private AlarmStateChange alarmStateChange;
    private AlarmDetail alarmDetail;
    private AlarmState alarmState;
    private AlarmConfiguration configuration;
    private AlarmMetrics metrics;
    private AlarmMetricStat metricStat;
    private AlarmMetric metric;
    private String resonData = "{\"statistic\":\"Average\",\"period\":300,\"threshold\":50.0}";

    @BeforeEach
    public void setup() {
        alarmStateChange = mock(AlarmStateChange.class);
        alarmDetail = mock(AlarmDetail.class);
        alarmState = mock(AlarmState.class);
        configuration = mock(AlarmConfiguration.class);
        metrics = mock(AlarmMetrics.class);
        metricStat = mock(AlarmMetricStat.class);
        metric = mock(AlarmMetric.class);
        testClass = new AlarmMetricConverter();
    }

    @Test
    public void convertAlarm_alarm() {
        expect(alarmStateChange.getDetail()).andReturn(alarmDetail).anyTimes();
        expect(alarmStateChange.getRegion()).andReturn("region").times(2);
        expect(alarmStateChange.getTime()).andReturn("time1").times(2);
        expect(alarmDetail.getState()).andReturn(alarmState).times(5);
        expect(alarmState.getValue()).andReturn("ALARM").times(2);
        expect(alarmState.getReasonData()).andReturn(resonData).times(2);
        expect(alarmDetail.getAlarmName()).andReturn("alarm1").times(2);
        expect(alarmDetail.getConfiguration()).andReturn(configuration).times(3);
        expect(configuration.getMetrics()).andReturn(ImmutableList.of(metrics)).times(2);
        expect(metrics.getMetricStat()).andReturn(metricStat).anyTimes();
        expect(metricStat.getMetric()).andReturn(metric).times(2);
        expect(metric.getNamespace()).andReturn("namespace").times(2);
        expect(metric.getName()).andReturn("metric").times(2);
        expect(metric.getDimensions()).andReturn(ImmutableMap.of("AutoScalingGroupName", "grp1")).times(2);


        replayAll();

        List<Map<String, String>> ret = testClass.convertAlarm(alarmStateChange);
        assertEquals(1, ret.size());

        verifyAll();
    }

    @Test
    public void convertAlarm_alarm_multiple_dimension() {
        expect(alarmStateChange.getDetail()).andReturn(alarmDetail).anyTimes();
        expect(alarmStateChange.getRegion()).andReturn("region").times(2);
        expect(alarmStateChange.getTime()).andReturn("time1").times(2);
        expect(alarmDetail.getState()).andReturn(alarmState).anyTimes();
        expect(alarmState.getValue()).andReturn("ALARM").times(2);
        expect(alarmState.getReasonData()).andReturn(resonData).times(2);
        expect(alarmDetail.getAlarmName()).andReturn("alarm1").times(2);
        expect(alarmDetail.getConfiguration()).andReturn(configuration).times(3);
        expect(configuration.getMetrics()).andReturn(ImmutableList.of(metrics)).times(2);
        expect(metrics.getMetricStat()).andReturn(metricStat).anyTimes();
        expect(metricStat.getMetric()).andReturn(metric).times(2);
        expect(metric.getNamespace()).andReturn("namespace").times(2);
        expect(metric.getName()).andReturn("metric").times(2);
        expect(metric.getDimensions()).andReturn(ImmutableMap.of("AutoScalingGroupName", "grp1",
                "instanceid", "inst1")).times(2);


        replayAll();

        List<Map<String, String>> ret = testClass.convertAlarm(alarmStateChange);
        assertEquals(1, ret.size());

        verifyAll();
    }

}
