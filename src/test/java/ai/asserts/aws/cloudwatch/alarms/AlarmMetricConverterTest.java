/*
 *  Copyright © 2022.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.cloudwatch.alarms;

import ai.asserts.aws.exporter.MetricSampleBuilder;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.prometheus.client.Collector;
import org.easymock.EasyMockSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.easymock.EasyMock.expect;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class AlarmMetricConverterTest extends EasyMockSupport {

    private MetricSampleBuilder sampleBuilder;
    private AlarmMetricConverter testClass;
    private Collector.MetricFamilySamples.Sample sample;
    private AlarmStateChanged alarmStateChanged;
    private AlarmDetail alarmDetail;
    private AlarmState alarmState;
    private AlarmConfiguration configuration;
    private AlarmMetrics metrics;
    private AlarmMetricStat metricStat;
    private AlarmMetric metric;

    @BeforeEach
    public void setup() {
        sampleBuilder = mock(MetricSampleBuilder.class);
        sample = mock(Collector.MetricFamilySamples.Sample.class);
        alarmStateChanged = mock(AlarmStateChanged.class);
        alarmDetail = mock(AlarmDetail.class);
        alarmState = mock(AlarmState.class);
        configuration = mock(AlarmConfiguration.class);
        metrics = mock(AlarmMetrics.class);
        metricStat = mock(AlarmMetricStat.class);
        metric = mock(AlarmMetric.class);
        testClass = new AlarmMetricConverter(sampleBuilder);
    }

    @Test
    public void convertAlarm_alarm() {
        expect(alarmStateChanged.getDetail()).andReturn(alarmDetail).times(6);
        expect(alarmStateChanged.getRegion()).andReturn("region");
        expect(alarmDetail.getState()).andReturn(alarmState);
        expect(alarmState.getValue()).andReturn("ALARM");
        expect(alarmDetail.getAlarmName()).andReturn("alarm1");
        expect(alarmStateChanged.getSource()).andReturn("src1").times(2);
        expect(alarmDetail.getConfiguration()).andReturn(configuration).times(3);
        expect(configuration.getMetrics()).andReturn(ImmutableList.of(metrics)).times(2);
        expect(metrics.getMetricStat()).andReturn(metricStat).times(3);
        expect(metricStat.getMetric()).andReturn(metric).times(2);
        expect(metric.getNamespace()).andReturn("namespace").times(2);
        expect(metric.getDimensions()).andReturn(ImmutableMap.of("AutoScalingGroupName", "grp1")).times(2);

        expect(sampleBuilder.buildSingleSample("ALERTS",
                ImmutableMap.<String, String>builder()
                        .put("alertgroup", "aws_exporter")
                        .put("alertname", "alarm1")
                        .put("alertstate", "firing")
                        .put("asserts_alert_category", "error")
                        .put("asserts_entity_type", "Service")
                        .put("asserts_severity", "warning")
                        .put("asserts_source", "src1")
                        .put("job", "grp1")
                        .put("namespace", "namespace")
                        .put("region", "region")
                        .put("service", "grp1")
                        .build(),
                1.0D))
                .andReturn(sample);

        replayAll();

        assertTrue(testClass.convertAlarm(alarmStateChanged));

        verifyAll();
    }

    @Test
    public void convertAlarm_alarm_no_dimesion() {
        expect(alarmStateChanged.getDetail()).andReturn(alarmDetail).times(6);
        expect(alarmStateChanged.getRegion()).andReturn("region");
        expect(alarmDetail.getState()).andReturn(alarmState);
        expect(alarmState.getValue()).andReturn("ALARM");
        expect(alarmDetail.getAlarmName()).andReturn("alarm1");
        expect(alarmStateChanged.getSource()).andReturn("src1").times(2);
        expect(alarmDetail.getConfiguration()).andReturn(configuration).times(3);
        expect(configuration.getMetrics()).andReturn(ImmutableList.of(metrics)).times(2);
        expect(metrics.getMetricStat()).andReturn(metricStat).times(3);
        expect(metricStat.getMetric()).andReturn(metric).times(2);
        expect(metric.getNamespace()).andReturn("namespace").times(2);
        expect(metric.getDimensions()).andReturn(ImmutableMap.of());
        expect(alarmStateChanged.getResources()).andReturn(ImmutableList.of("resource1")).times(2);
        replayAll();

        assertFalse(testClass.convertAlarm(alarmStateChanged));

        verifyAll();
    }

    @Test
    public void convertAlarm_alarm_stop() {
        expect(alarmStateChanged.getDetail()).andReturn(alarmDetail).times(7);
        expect(alarmDetail.getState()).andReturn(alarmState);
        expect(alarmState.getValue()).andReturn("OK");
        expect(alarmDetail.getAlarmName()).andReturn("alarm1");
        expect(alarmDetail.getConfiguration()).andReturn(configuration).times(3);
        expect(configuration.getMetrics()).andReturn(ImmutableList.of(metrics)).times(2);
        expect(metrics.getMetricStat()).andReturn(metricStat).times(3);
        expect(metricStat.getMetric()).andReturn(metric).times(2);
        expect(metric.getNamespace()).andReturn("namespace").times(2);
        expect(metric.getDimensions()).andReturn(ImmutableMap.of("AutoScalingGroupName", "grp1")).times(2);

        replayAll();

        assertTrue(testClass.convertAlarm(alarmStateChanged));

        verifyAll();
    }

    @Test
    public void convertAlarm_alarm_stop_no_dimension() {
        expect(alarmStateChanged.getDetail()).andReturn(alarmDetail).times(7);
        expect(alarmDetail.getState()).andReturn(alarmState);
        expect(alarmState.getValue()).andReturn("OK");
        expect(alarmDetail.getAlarmName()).andReturn("alarm1");
        expect(alarmDetail.getConfiguration()).andReturn(configuration).times(3);
        expect(configuration.getMetrics()).andReturn(ImmutableList.of(metrics)).times(2);
        expect(metrics.getMetricStat()).andReturn(metricStat).times(3);
        expect(metricStat.getMetric()).andReturn(metric).times(2);
        expect(metric.getNamespace()).andReturn("namespace").times(2);
        expect(metric.getDimensions()).andReturn(ImmutableMap.of());
        expect(alarmStateChanged.getResources()).andReturn(ImmutableList.of("resource1")).times(2);

        replayAll();

        assertFalse(testClass.convertAlarm(alarmStateChanged));

        verifyAll();
    }

}
