/*
 *  Copyright © 2022.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.cloudwatch.alarms;

import ai.asserts.aws.ObjectMapperFactory;
import ai.asserts.aws.account.AccountTenantMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.easymock.EasyMockSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.easymock.EasyMock.anyString;
import static org.easymock.EasyMock.expect;
import static org.junit.jupiter.api.Assertions.assertAll;
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
        AccountTenantMapper accountTenantMapper = mock(AccountTenantMapper.class);
        testClass = new AlarmMetricConverter(new ObjectMapperFactory(), accountTenantMapper);
        expect(accountTenantMapper.getTenantName(anyString())).andReturn("tenant").anyTimes();
    }

    @Test
    public void convertAlarm_alarm() {
        String alertTime = Long.toString(Instant.now().minusSeconds(3600).getEpochSecond());

        expect(alarmStateChange.getDetail()).andReturn(alarmDetail).anyTimes();
        expect(alarmStateChange.getRegion()).andReturn("region").times(2);
        expect(alarmStateChange.getAccount()).andReturn("123456789").anyTimes();
        expect(alarmStateChange.getTime()).andReturn(alertTime).anyTimes();
        expect(alarmDetail.getAlarmName()).andReturn("alarm1").anyTimes();
        expect(alarmDetail.getState()).andReturn(alarmState).times(5);
        expect(alarmState.getValue()).andReturn("ALARM").times(2);
        String responseData = "{\"statistic\":\"Average\",\"period\":300,\"threshold\":50.0}";
        expect(alarmState.getReasonData()).andReturn(responseData).times(2);
        expect(alarmDetail.getConfiguration()).andReturn(configuration).times(3);
        expect(configuration.getMetrics()).andReturn(ImmutableList.of(metrics)).times(2);
        expect(metrics.getMetricStat()).andReturn(metricStat).anyTimes();
        expect(metricStat.getMetric()).andReturn(metric).anyTimes();
        expect(metricStat.getStat()).andReturn("Average").anyTimes();
        expect(metricStat.getPeriod()).andReturn(300).anyTimes();
        expect(metric.getNamespace()).andReturn("namespace").anyTimes();
        expect(metric.getName()).andReturn("metric").anyTimes();
        ImmutableMap<String, String> dimensions = ImmutableMap.of(
                "AutoScalingGroupName", "grp1",
                "AnotherDimension", "value"
        );
        expect(metric.getDimensions()).andReturn(dimensions).anyTimes();

        replayAll();

        List<Map<String, String>> ret = testClass.convertAlarm(alarmStateChange);
        assertEquals(1, ret.size());
        Map<String, String> labels = ret.get(0);
        assertAll(
                () -> assertEquals(alertTime, labels.get("timestamp")),
                () -> assertEquals("123456789", labels.get("account_id")),
                () -> assertEquals("alarm1", labels.get("alertname")),
                () -> assertEquals("namespace", labels.get("namespace")),
                () -> assertEquals("metric", labels.get("metric_name")),
                () -> assertEquals("300", labels.get("metric_period")),
                () -> assertEquals("region", labels.get("region")),
                () -> assertEquals("50.0", labels.get("threshold")),
                () -> assertEquals("grp1", labels.get("d_AutoScalingGroupName")),
                () -> assertEquals("value", labels.get("d_AnotherDimension"))
        );
        verifyAll();
    }

    @Test
    public void simplifyAlarmName() {
        Map<String, String> labels = new TreeMap<>();
        labels.put("alarm_name",
                "TargetTracking-table/GameScores/index/GameTitle-TopScore-index-AlarmLow-700cf83c-e41f-4349-a94a-88da343f4823");
        labels.put("job", "GameScores");
        labels.put("metric_name", "ReadCapacityUnits");

        testClass.simplifyAlarmName(labels);
        assertEquals("TargetTracking-table/GameScores/index/GameTitle-TopScore-index-AlarmLow-700cf83c-e41f-4349-a94a-88da343f4823",
                labels.get("original_alarm_name"));
        assertEquals("GameTitle-TopScore-index ReadCapacityUnits Low", labels.get("alarm_name"));


        labels.put("alarm_name", "TargetTracking-table/Rides-AlarmLow-1547ee0e-533a-4275-ae53-276bf265ea28");
        labels.put("job", "Rides");
        labels.put("metric_name", "ReadCapacityUnits");

        testClass.simplifyAlarmName(labels);
        assertEquals("TargetTracking-table/Rides-AlarmLow-1547ee0e-533a-4275-ae53-276bf265ea28",
                labels.get("original_alarm_name"));
        assertEquals("ReadCapacityUnits Low", labels.get("alarm_name"));

        labels.put("alarm_name","ride-bookings-ThrottledReads");
        labels.put("job","ride-bookings");

        testClass.simplifyAlarmName(labels);
        assertEquals("ride-bookings-ThrottledReads", labels.get("original_alarm_name"));
        assertEquals("ThrottledReads", labels.get("alarm_name"));
    }
}
