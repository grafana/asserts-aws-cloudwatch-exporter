/*
 * Copyright © 2021
 * Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.lambda;

import ai.asserts.aws.AWSClientProvider;
import ai.asserts.aws.MetricNameUtil;
import ai.asserts.aws.Resource;
import ai.asserts.aws.ResourceMapper;
import ai.asserts.aws.ResourceType;
import ai.asserts.aws.cloudwatch.prometheus.GaugeExporter;
import ai.asserts.aws.cloudwatch.config.ScrapeConfig;
import ai.asserts.aws.cloudwatch.config.ScrapeConfigProvider;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import org.easymock.EasyMockSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.EventSourceMappingConfiguration;
import software.amazon.awssdk.services.lambda.model.ListEventSourceMappingsResponse;

import java.time.Instant;
import java.util.Optional;

import static org.easymock.EasyMock.expect;

public class LambdaEventSourceExporterTest extends EasyMockSupport {
    private ScrapeConfigProvider scrapeConfigProvider;
    private AWSClientProvider awsClientProvider;
    private LambdaClient lambdaClient;
    private MetricNameUtil metricNameUtil;
    private GaugeExporter gaugeExporter;
    private ResourceMapper resourceMapper;
    private LambdaEventSourceExporter testClass;
    private Instant now;

    @BeforeEach
    public void setup() {
        scrapeConfigProvider = mock(ScrapeConfigProvider.class);
        awsClientProvider = mock(AWSClientProvider.class);
        metricNameUtil = mock(MetricNameUtil.class);
        gaugeExporter = mock(GaugeExporter.class);
        lambdaClient = mock(LambdaClient.class);
        resourceMapper = mock(ResourceMapper.class);
        now = Instant.now();
        testClass = new LambdaEventSourceExporter(scrapeConfigProvider, awsClientProvider,
                metricNameUtil, gaugeExporter, resourceMapper) {
            @Override
            Instant now() {
                return now;
            }
        };
    }

    @Test
    public void exportEventSourceMappings() {
        expect(scrapeConfigProvider.getScrapeConfig()).andReturn(
                ScrapeConfig.builder()
                        .regions(ImmutableSet.of("region1"))
                        .build()
        );
        expect(awsClientProvider.getLambdaClient("region1")).andReturn(lambdaClient);
        expect(lambdaClient.listEventSourceMappings()).andReturn(
                ListEventSourceMappingsResponse.builder()
                        .eventSourceMappings(ImmutableList.of(
                                EventSourceMappingConfiguration.builder()
                                        .functionArn("fn1_arn")
                                        .eventSourceArn("queue_arn")
                                        .build(),
                                EventSourceMappingConfiguration.builder()
                                        .functionArn("fn2_arn")
                                        .eventSourceArn("table_arn")
                                        .build()
                        ))
                        .build()
        );

        expect(resourceMapper.map("fn1_arn"))
                .andReturn(Optional.of(new Resource(ResourceType.LambdaFunction, "fn1_arn", "fn1")));
        expect(resourceMapper.map("queue_arn"))
                .andReturn(Optional.of(new Resource(ResourceType.SQSQueue, "queue_arn", "queue")));
        expect(resourceMapper.map("fn2_arn"))
                .andReturn(Optional.of(new Resource(ResourceType.LambdaFunction, "fn2_arn", "fn2")));
        expect(resourceMapper.map("table_arn"))
                .andReturn(Optional.of(new Resource(ResourceType.DynamoDBTable, "table_arn", "table")));

        expect(metricNameUtil.getMetricPrefix("AWS/Lambda")).andReturn("aws_lambda").anyTimes();
        String help = "Metric with lambda event source information";
        gaugeExporter.exportMetric("aws_lambda_event_source", help,
                ImmutableMap.of(
                        "region", "region1",
                        "event_source_name", "queue",
                        "event_source_type", "SQSQueue",
                        "lambda_function", "fn1"
                ),
                now, 1.0D);

        gaugeExporter.exportMetric("aws_lambda_event_source", help,
                ImmutableMap.of(
                        "region", "region1",
                        "event_source_name", "table",
                        "event_source_type", "DynamoDBTable",
                        "lambda_function", "fn2"
                ),
                now, 1.0D);

        replayAll();
        testClass.run();
        verifyAll();
    }
}
