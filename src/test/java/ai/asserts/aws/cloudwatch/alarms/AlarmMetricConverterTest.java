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
        expect(alarmStateChange.getRegion()).andReturn("region");
        expect(alarmStateChange.getTime()).andReturn("time1");
        expect(alarmDetail.getState()).andReturn(alarmState).times(2);
        expect(alarmState.getValue()).andReturn("ALARM").times(2);
        expect(alarmDetail.getAlarmName()).andReturn("alarm1");
        expect(alarmDetail.getConfiguration()).andReturn(configuration).times(3);
        expect(configuration.getMetrics()).andReturn(ImmutableList.of(metrics)).times(2);
        expect(metrics.getMetricStat()).andReturn(metricStat).anyTimes();
        expect(metricStat.getStat()).andReturn("stat").times(2);
        expect(metricStat.getUnit()).andReturn("unit").times(2);
        expect(metricStat.getMetric()).andReturn(metric).times(2);
        expect(metric.getNamespace()).andReturn("namespace").times(2);
        expect(metric.getName()).andReturn("metric1").times(2);
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
        expect(alarmDetail.getState()).andReturn(alarmState).times(3);
        expect(alarmState.getValue()).andReturn("ALARM").times(3);
        expect(alarmDetail.getAlarmName()).andReturn("alarm1").times(2);
        expect(alarmDetail.getConfiguration()).andReturn(configuration).times(3);
        expect(configuration.getMetrics()).andReturn(ImmutableList.of(metrics)).times(2);
        expect(metrics.getMetricStat()).andReturn(metricStat).anyTimes();
        expect(metricStat.getStat()).andReturn("stat").times(4);
        expect(metricStat.getUnit()).andReturn("unit").times(4);
        expect(metricStat.getMetric()).andReturn(metric).times(2);
        expect(metric.getNamespace()).andReturn("namespace").times(4);
        expect(metric.getName()).andReturn("metric1").times(4);
        expect(metric.getDimensions()).andReturn(ImmutableMap.of("AutoScalingGroupName", "grp1",
                "instanceid", "inst1")).times(2);


        replayAll();

        List<Map<String, String>> ret = testClass.convertAlarm(alarmStateChange);
        assertEquals(2, ret.size());

        verifyAll();
    }

    @Test
    public void convertAlarm_alarm_no_dimesion() {
        expect(alarmStateChange.getDetail()).andReturn(alarmDetail).times(5);
        expect(alarmDetail.getState()).andReturn(alarmState);
        expect(alarmState.getValue()).andReturn("ALARM");
        expect(alarmDetail.getConfiguration()).andReturn(configuration).times(3);
        expect(configuration.getMetrics()).andReturn(ImmutableList.of(metrics)).times(2);
        expect(metrics.getMetricStat()).andReturn(metricStat).times(3);
        expect(metricStat.getMetric()).andReturn(metric).times(2);
        expect(metric.getDimensions()).andReturn(ImmutableMap.of());
        replayAll();

        List<Map<String, String>> ret = testClass.convertAlarm(alarmStateChange);
        assertEquals(0, ret.size());

        verifyAll();
    }

    @Test
    public void convertAlarm_alarm_stop() {
        expect(alarmStateChange.getDetail()).andReturn(alarmDetail).anyTimes();
        expect(alarmDetail.getState()).andReturn(alarmState).times(2);
        expect(alarmState.getValue()).andReturn("OK").times(2);
        expect(alarmDetail.getAlarmName()).andReturn("alarm1");
        expect(alarmDetail.getConfiguration()).andReturn(configuration).times(3);
        expect(configuration.getMetrics()).andReturn(ImmutableList.of(metrics)).times(2);
        expect(metrics.getMetricStat()).andReturn(metricStat).anyTimes();
        expect(metricStat.getMetric()).andReturn(metric).times(2);
        expect(metricStat.getStat()).andReturn("stat").times(2);
        expect(metricStat.getUnit()).andReturn("unit").times(2);
        expect(metric.getNamespace()).andReturn("namespace").times(2);
        expect(metric.getName()).andReturn("metric1").times(2);
        expect(metric.getDimensions()).andReturn(ImmutableMap.of("AutoScalingGroupName", "grp1")).times(2);

        replayAll();

        List<Map<String, String>> ret = testClass.convertAlarm(alarmStateChange);
        assertEquals(1, ret.size());

        verifyAll();
    }

    @Test
    public void convertAlarm_alarm_stop_no_dimension() {
        expect(alarmStateChange.getDetail()).andReturn(alarmDetail).times(7);
        expect(alarmDetail.getState()).andReturn(alarmState);
        expect(alarmState.getValue()).andReturn("OK");
        expect(alarmDetail.getAlarmName()).andReturn("alarm1");
        expect(alarmDetail.getConfiguration()).andReturn(configuration).times(3);
        expect(configuration.getMetrics()).andReturn(ImmutableList.of(metrics)).times(2);
        expect(metrics.getMetricStat()).andReturn(metricStat).times(3);
        expect(metricStat.getMetric()).andReturn(metric).times(2);
        expect(metric.getDimensions()).andReturn(ImmutableMap.of());


        replayAll();

        List<Map<String, String>> ret = testClass.convertAlarm(alarmStateChange);
        assertEquals(0, ret.size());

        verifyAll();
    }

}
