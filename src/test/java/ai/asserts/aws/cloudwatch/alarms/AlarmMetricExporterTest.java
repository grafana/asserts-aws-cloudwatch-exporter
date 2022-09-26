/*
 *  Copyright © 2022.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.cloudwatch.alarms;

import ai.asserts.aws.MetricNameUtil;
import ai.asserts.aws.exporter.BasicMetricCollector;
import ai.asserts.aws.exporter.MetricSampleBuilder;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.prometheus.client.Collector;
import org.easymock.EasyMockSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;

import static org.easymock.EasyMock.anyObject;
import static org.easymock.EasyMock.expect;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class AlarmMetricExporterTest extends EasyMockSupport {

    private MetricSampleBuilder sampleBuilder;
    private BasicMetricCollector basicMetricCollector;
    private AlarmMetricConverter alarmMetricConverter;
    private AlarmMetricExporter testClass;
    private Collector.MetricFamilySamples.Sample sample;
    private Collector.MetricFamilySamples samples;
    private Instant now;

    @BeforeEach
    public void setup() {
        sampleBuilder = mock(MetricSampleBuilder.class);
        sample = mock(Collector.MetricFamilySamples.Sample.class);
        samples = mock(Collector.MetricFamilySamples.class);
        basicMetricCollector = mock(BasicMetricCollector.class);
        alarmMetricConverter = mock(AlarmMetricConverter.class);
        now = Instant.now();
        testClass = new AlarmMetricExporter(sampleBuilder, basicMetricCollector, alarmMetricConverter) {
            @Override
            Instant now() {
                return now;
            }
        };
    }

    @Test
    public void processMetric_alert() {
        addLabels("ALARM");
        assertEquals(1, testClass.getAlarmLabels().size());
    }

    @Test
    public void processMetric_ok() {
        addLabels("ALARM");
        assertEquals(1, testClass.getAlarmLabels().size());
        addLabels("OK");
        assertEquals(0, testClass.getAlarmLabels().size());
    }

    @Test
    public void collect() {
        long timestamp = Instant.parse("2022-02-07T09:56:46Z").getEpochSecond();
        expect(sampleBuilder.buildSingleSample("aws_cloudwatch_alarm",
                new ImmutableMap.Builder<String, String>()
                        .put("account_id", "account")
                        .put("metric_name", "m1")
                        .put("alarm_name", "a1")
                        .put("namespace", "n1")
                        .put("region", "us-west-2")
                        .build(), 1.0)).andReturn(Optional.of(sample));
        expect(sampleBuilder.buildFamily(ImmutableList.of(sample))).andReturn(Optional.of(samples));
        SortedMap<String, String> labels = new TreeMap<>(new ImmutableMap.Builder<String, String>()
                .put("account_id", "account")
                .put("alarm_name", "a1")
                .put("namespace", "n1")
                .put("region", "us-west-2").build());

        alarmMetricConverter.simplifyAlarmName(anyObject(Map.class));

        basicMetricCollector.recordHistogram(MetricNameUtil.EXPORTER_DELAY_SECONDS, labels,
                now.minusSeconds(timestamp).getEpochSecond());
        replayAll();
        addLabels("ALARM");
        assertEquals(1, testClass.getAlarmLabels().size());
        testClass.collect();
        verifyAll();
    }

    private void addLabels(String state) {
        Map<String, String> labels = new TreeMap<>(new ImmutableMap.Builder<String, String>()
                .put("state", state)
                .put("account_id", "account")
                .put("namespace", "n1")
                .put("metric_name", "m1")
                .put("alarm_name", "a1")
                .put("timestamp", "2022-02-07T09:56:46Z")
                .put("region", "us-west-2").build());
        testClass.processMetric(ImmutableList.of(labels));
    }
}
