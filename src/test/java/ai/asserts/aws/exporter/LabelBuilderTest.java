/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.exporter;

import ai.asserts.aws.MetricNameUtil;
import ai.asserts.aws.ScrapeConfigProvider;
import ai.asserts.aws.cloudwatch.query.MetricQuery;
import ai.asserts.aws.lambda.LambdaLabelConverter;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;
import org.easymock.EasyMockSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.cloudwatch.model.Dimension;
import software.amazon.awssdk.services.cloudwatch.model.Metric;

import java.util.Map;
import java.util.Optional;

import static ai.asserts.aws.model.CWNamespace.ecs_containerinsights;
import static ai.asserts.aws.model.CWNamespace.lambda;
import static ai.asserts.aws.model.CWNamespace.sqs;
import static org.easymock.EasyMock.expect;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class LabelBuilderTest extends EasyMockSupport {
    private ScrapeConfigProvider scrapeConfigProvider;
    private LambdaLabelConverter lambdaLabelConverter;
    private MetricNameUtil metricNameUtil;
    private LabelBuilder labelBuilder;
    private Dimension functionDimension;

    @BeforeEach
    public void setup() {
        scrapeConfigProvider = mock(ScrapeConfigProvider.class);
        lambdaLabelConverter = mock(LambdaLabelConverter.class);
        metricNameUtil = mock(MetricNameUtil.class);
        labelBuilder = new LabelBuilder(scrapeConfigProvider, metricNameUtil, lambdaLabelConverter);
        functionDimension = Dimension.builder()
                .name("FunctionName")
                .value("function1")
                .build();
    }

    @Test
    void buildLabels_LambdaMetric() {
        expect(scrapeConfigProvider.getStandardNamespace("AWS/Lambda")).andReturn(Optional.of(lambda));
        expect(lambdaLabelConverter.shouldUseForNamespace("AWS/Lambda")).andReturn(true);
        expect(lambdaLabelConverter.convert(functionDimension)).andReturn(ImmutableMap.of(
                "d_function_name", "function1"
        ));

        MetricQuery metricQuery = MetricQuery.builder()
                .metric(Metric.builder()
                        .metricName("Invocations")
                        .namespace("AWS/Lambda")
                        .dimensions(functionDimension)
                        .build())
                .build();

        replayAll();

        Map<String, String> labels = labelBuilder.buildLabels("account", "region1", metricQuery);
        verifyAll();

        assertEquals(ImmutableSortedMap.naturalOrder()
                .put("account_id", "account")
                .put("region", "region1")
                .put("cw_namespace", "AWS/Lambda")
                .put("namespace", "AWS/Lambda")
                .put("d_function_name", "function1")
                .build(), labels);
    }

    @Test
    void buildLabels_LambdaInsightsMetric() {
        expect(scrapeConfigProvider.getStandardNamespace("LambdaInsights")).andReturn(Optional.of(lambda));
        expect(lambdaLabelConverter.shouldUseForNamespace("LambdaInsights")).andReturn(false);
        expect(metricNameUtil.toSnakeCase("function_name")).andReturn("function_name");

        functionDimension = Dimension.builder()
                .name("function_name")
                .value("function1")
                .build();

        MetricQuery metricQuery = MetricQuery.builder()
                .metric(Metric.builder()
                        .metricName("memory_utilization")
                        .namespace("LambdaInsights")
                        .dimensions(functionDimension)
                        .build())
                .build();

        replayAll();

        Map<String, String> labels = labelBuilder.buildLabels("account", "region1", metricQuery);
        verifyAll();

        assertEquals(ImmutableSortedMap.naturalOrder()
                .put("account_id", "account")
                .put("region", "region1")
                .put("cw_namespace", "AWS/Lambda")
                .put("namespace", "AWS/Lambda")
                .put("d_function_name", "function1")
                .build(), labels);
    }

    @Test
    void buildLabels_SQSMetric() {
        expect(scrapeConfigProvider.getStandardNamespace("AWS/SQS")).andReturn(Optional.of(sqs));
        expect(lambdaLabelConverter.shouldUseForNamespace("AWS/SQS")).andReturn(false);
        expect(metricNameUtil.toSnakeCase("QueueName")).andReturn("queue_name");

        MetricQuery metricQuery = MetricQuery.builder()
                .metric(Metric.builder()
                        .metricName("NumberOfMessagesReceived")
                        .namespace("AWS/SQS")
                        .dimensions(Dimension.builder()
                                .name("QueueName")
                                .value("queue1")
                                .build())
                        .build())
                .build();

        replayAll();

        Map<String, String> labels = labelBuilder.buildLabels("account", "region1", metricQuery);
        verifyAll();

        assertEquals(ImmutableSortedMap.naturalOrder()
                .put("account_id", "account")
                .put("region", "region1")
                .put("cw_namespace", "AWS/SQS")
                .put("namespace", "AWS/SQS")
                .put("d_queue_name", "queue1")
                .build(), labels);
    }

    @Test
    void buildLabels_ECS_ContainerInsights_Metric() {
        expect(scrapeConfigProvider.getStandardNamespace("ECS/ContainerInsights"))
                .andReturn(Optional.of(ecs_containerinsights));
        expect(lambdaLabelConverter.shouldUseForNamespace("ECS/ContainerInsights")).andReturn(false);
        expect(metricNameUtil.toSnakeCase("ServiceName")).andReturn("service_name");

        replayAll();
        Map<String, String> labels = labelBuilder.buildLabels("account", "region1", MetricQuery.builder()
                .metric(Metric.builder()
                        .metricName("CPUUtilization")
                        .namespace("ECS/ContainerInsights")
                        .dimensions(Dimension.builder()
                                .name("ServiceName")
                                .value("service-name")
                                .build())
                        .build())
                .build());
        verifyAll();

        assertEquals(ImmutableSortedMap.naturalOrder()
                .put("account_id", "account")
                .put("region", "region1")
                .put("cw_namespace", "AWS/ECS")
                .put("namespace", "AWS/ECS")
                .put("d_service_name", "service-name")
                .build(), labels);
    }
}
