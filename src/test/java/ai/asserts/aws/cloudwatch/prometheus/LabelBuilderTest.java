/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.cloudwatch.prometheus;

import ai.asserts.aws.MetricNameUtil;
import ai.asserts.aws.cloudwatch.query.MetricQuery;
import com.google.common.collect.ImmutableMap;
import org.easymock.EasyMockSupport;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.cloudwatch.model.Dimension;
import software.amazon.awssdk.services.cloudwatch.model.Metric;

import java.util.Map;

import static org.easymock.EasyMock.expect;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class LabelBuilderTest extends EasyMockSupport {
    @Test
    void buildLabels_LambdaMetric() {
        MetricNameUtil metricNameUtil = mock(MetricNameUtil.class);
        LabelBuilder labelBuilder = new LabelBuilder(metricNameUtil);

        expect(metricNameUtil.toSnakeCase("FunctionName")).andReturn("function_name");

        replayAll();
        Map<String, String> labels = labelBuilder.buildLabels("region1", MetricQuery.builder()
                .metric(Metric.builder()
                        .metricName("Invocations")
                        .namespace("AWS/Lambda")
                        .dimensions(Dimension.builder()
                                .name("FunctionName")
                                .value("function1")
                                .build())
                        .build())
                .build());
        verifyAll();

        assertEquals(ImmutableMap.of(
                "region", "region1",
                "d_function_name", "function1",
                "job", "function1"
        ), labels);
    }

    @Test
    void buildLabels_LambdaInsightsMetric() {
        MetricNameUtil metricNameUtil = mock(MetricNameUtil.class);
        LabelBuilder labelBuilder = new LabelBuilder(metricNameUtil);

        expect(metricNameUtil.toSnakeCase("function_name")).andReturn("function_name");

        replayAll();
        Map<String, String> labels = labelBuilder.buildLabels("region1", MetricQuery.builder()
                .metric(Metric.builder()
                        .metricName("memory_utilization")
                        .namespace("LambdaInsights")
                        .dimensions(Dimension.builder()
                                .name("function_name")
                                .value("function1")
                                .build())
                        .build())
                .build());
        verifyAll();

        assertEquals(ImmutableMap.of(
                "region", "region1",
                "d_function_name", "function1",
                "job", "function1"
        ), labels);
    }

    @Test
    void buildLabels_SQSMetric() {
        MetricNameUtil metricNameUtil = mock(MetricNameUtil.class);
        LabelBuilder labelBuilder = new LabelBuilder(metricNameUtil);

        expect(metricNameUtil.toSnakeCase("QueueName")).andReturn("queue_name");

        replayAll();
        Map<String, String> labels = labelBuilder.buildLabels("region1", MetricQuery.builder()
                .metric(Metric.builder()
                        .metricName("NumberOfMessagesReceived")
                        .namespace("AWS/SQS")
                        .dimensions(Dimension.builder()
                                .name("QueueName")
                                .value("queue1")
                                .build())
                        .build())
                .build());
        verifyAll();

        assertEquals(ImmutableMap.of(
                "region", "region1",
                "d_queue_name", "queue1",
                "topic", "queue1"
        ), labels);
    }
}
