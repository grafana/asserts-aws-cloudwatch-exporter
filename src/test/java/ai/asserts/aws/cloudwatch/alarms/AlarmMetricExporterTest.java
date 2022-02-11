/*
 *  Copyright © 2022.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.cloudwatch.alarms;

import ai.asserts.aws.exporter.BasicMetricCollector;
import ai.asserts.aws.exporter.MetricSampleBuilder;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.prometheus.client.Collector;
import org.easymock.EasyMockSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

import static org.easymock.EasyMock.expect;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class AlarmMetricExporterTest extends EasyMockSupport {

    private MetricSampleBuilder sampleBuilder;
    private BasicMetricCollector basicMetricCollector;
    private AlarmMetricExporter testClass;
    private Collector.MetricFamilySamples.Sample sample;
    private Collector.MetricFamilySamples samples;
    private Instant now;

    @BeforeEach
    public void setup() {
        now = Instant.now();
        sampleBuilder = mock(MetricSampleBuilder.class);
        sample = mock(Collector.MetricFamilySamples.Sample.class);
        samples = mock(Collector.MetricFamilySamples.class);
        basicMetricCollector = mock(BasicMetricCollector.class);
        testClass = new AlarmMetricExporter(sampleBuilder, basicMetricCollector);
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
                ImmutableMap.of("metric_name", "m1", "alertname", "a1", "namespace", "n1",
                        "region", "us-west-2"), 1.0, now.minusSeconds(30).getEpochSecond())).andReturn(sample);
        expect(sampleBuilder.buildSingleSample("aws_cloudwatch_alarm",
                ImmutableMap.of("metric_name", "m1", "alertname", "a1", "namespace", "n1",
                        "region", "us-west-2"), 1.0, now.getEpochSecond())).andReturn(sample);
        expect(sampleBuilder.buildFamily(ImmutableList.of(sample))).andReturn(samples).times(2);
        SortedMap<String, String> labels = new TreeMap<>() {{
            put("alertname", "a1");
            put("namespace", "n1");
            put("region", "us-west-2");
        }};
        basicMetricCollector.recordHistogram("aws_cloudwatch_alarm", labels, Instant.now().minusSeconds(timestamp).getEpochSecond());
        replayAll();
        addLabels("ALARM");
        assertEquals(1, testClass.getAlarmLabels().size());
        testClass.collect();
        testClass.collect();
        verifyAll();
    }

    private void addLabels(String state) {
        Map<String, String> labels = new HashMap<>() {{
            put("state", state);
            put("namespace", "n1");
            put("metric_name", "m1");
            put("alertname", "a1");
            put("timestamp", "2022-02-07T09:56:46Z");
            put("region", "us-west-2");
        }};
        testClass.processMetric(ImmutableList.of(labels));
    }
}
