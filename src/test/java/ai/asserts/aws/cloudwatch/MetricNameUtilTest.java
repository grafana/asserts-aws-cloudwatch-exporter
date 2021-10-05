/*
 * Copyright © 2021
 * Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.cloudwatch;

import ai.asserts.aws.MetricNameUtil;
import ai.asserts.aws.cloudwatch.model.MetricStat;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.cloudwatch.model.Dimension;
import software.amazon.awssdk.services.cloudwatch.model.Metric;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class MetricNameUtilTest {
    @Test
    void toSnakeCase() {
        MetricNameUtil metricNameUtil = new MetricNameUtil();
        assertEquals("method_duration_seconds", metricNameUtil.toSnakeCase("MethodDurationSeconds"));
        assertEquals("cpu_load15", metricNameUtil.toSnakeCase("CPULoad15"));
    }

    @Test
    void exportedMetricName() {
        MetricNameUtil metricNameUtil = new MetricNameUtil();
        assertEquals("aws_lambda_invocations_max", metricNameUtil.exportedMetricName(Metric.builder()
                .namespace("AWS/Lambda")
                .metricName("Invocations")
                .build(), MetricStat.Maximum));
        assertEquals("aws_lambda_invocations_avg", metricNameUtil.exportedMetricName(Metric.builder()
                .namespace("AWS/Lambda")
                .metricName("Invocations")
                .build(), MetricStat.Average));
    }

    @Test
    void exportedMetric() {
        MetricNameUtil metricNameUtil = new MetricNameUtil();
        assertEquals(
                "aws_lambda_invocations_max{d_function_name=\"function1\", d_resource=\"resource1\"}",
                metricNameUtil.exportedMetric(Metric.builder()
                        .namespace("AWS/Lambda")
                        .metricName("Invocations")
                        .dimensions(
                                Dimension.builder()
                                        .name("FunctionName")
                                        .value("function1")
                                        .build(),
                                Dimension.builder()
                                        .name("Resource")
                                        .value("resource1")
                                        .build()
                        )
                        .build(), MetricStat.Maximum));
    }
}
