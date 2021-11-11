/*
 * Copyright © 2021
 * Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws;

import ai.asserts.aws.cloudwatch.model.MetricStat;
import ai.asserts.aws.cloudwatch.query.MetricQuery;
import ai.asserts.aws.resource.Resource;
import ai.asserts.aws.resource.ResourceType;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.cloudwatch.model.Dimension;
import software.amazon.awssdk.services.cloudwatch.model.Metric;
import software.amazon.awssdk.services.resourcegroupstaggingapi.model.Tag;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class MetricNameUtilTest {
    @Test
    void toSnakeCase() {
        MetricNameUtil util = new MetricNameUtil();
        assertEquals("method_duration_seconds", util.toSnakeCase("MethodDurationSeconds"));
        assertEquals("cpu_load15", util.toSnakeCase("CPULoad15"));
        assertEquals("cpu_load", util.toSnakeCase("cpu-load"));
        assertEquals("tag_lambda_console_blueprint", util.toSnakeCase("tag_lambda_console:blueprint"));
    }

    @Test
    void getMetricPrefix() {
        MetricNameUtil util = new MetricNameUtil();
        assertEquals("aws_lambda", util.getMetricPrefix("AWS/Lambda"));
        assertEquals("aws_ecs", util.getMetricPrefix("AWS/ECS"));
        assertEquals("aws_ecs_containerinsights", util.getMetricPrefix("ECS/ContainerInsights"));
        assertEquals("aws_lambda", util.getMetricPrefix("LambdaInsights"));
        assertEquals("aws_sqs", util.getMetricPrefix("AWS/SQS"));
        assertEquals("aws_s3", util.getMetricPrefix("AWS/S3"));
        assertEquals("aws_dynamodb", util.getMetricPrefix("AWS/DynamoDB"));
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
                "aws_lambda_invocations_max{d_function_name=\"function1\", d_resource=\"resource1\", tag_tag1=\"value\"}",
                metricNameUtil.exportedMetric(MetricQuery.builder()
                        .resource(Resource.builder()
                                .type(ResourceType.LambdaFunction)
                                .name("function-1")
                                .tags(ImmutableList.of(Tag.builder()
                                        .key("tag1")
                                        .value("value")
                                        .build()))
                                .build())
                        .metric(Metric.builder()
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
                                                .build())
                                .build())
                        .metricStat(MetricStat.Maximum)
                        .build()));
    }
}
