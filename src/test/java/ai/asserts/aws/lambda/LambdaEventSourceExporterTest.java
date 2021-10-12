/*
 * Copyright © 2021
 * Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.lambda;

import ai.asserts.aws.AWSClientProvider;
import ai.asserts.aws.MetricNameUtil;
import ai.asserts.aws.cloudwatch.config.NamespaceConfig;
import ai.asserts.aws.cloudwatch.config.ScrapeConfig;
import ai.asserts.aws.cloudwatch.config.ScrapeConfigProvider;
import ai.asserts.aws.cloudwatch.prometheus.GaugeExporter;
import ai.asserts.aws.resource.Resource;
import ai.asserts.aws.resource.ResourceMapper;
import ai.asserts.aws.resource.TagFilterResourceProvider;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import org.easymock.EasyMockSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.EventSourceMappingConfiguration;
import software.amazon.awssdk.services.lambda.model.ListEventSourceMappingsResponse;
import software.amazon.awssdk.services.resourcegroupstaggingapi.model.Tag;

import java.time.Instant;
import java.util.Optional;

import static ai.asserts.aws.resource.ResourceType.DynamoDBTable;
import static ai.asserts.aws.resource.ResourceType.LambdaFunction;
import static ai.asserts.aws.resource.ResourceType.SQSQueue;
import static org.easymock.EasyMock.expect;

public class LambdaEventSourceExporterTest extends EasyMockSupport {
    private LambdaClient lambdaClient;
    private MetricNameUtil metricNameUtil;
    private GaugeExporter gaugeExporter;
    private ResourceMapper resourceMapper;
    private NamespaceConfig namespaceConfig;
    private Tag tag;
    private TagFilterResourceProvider tagFilterResourceProvider;
    private LambdaEventSourceExporter testClass;
    private Instant now;

    @BeforeEach
    public void setup() {
        metricNameUtil = mock(MetricNameUtil.class);
        gaugeExporter = mock(GaugeExporter.class);
        lambdaClient = mock(LambdaClient.class);
        resourceMapper = mock(ResourceMapper.class);
        now = Instant.now();
        tag = Tag.builder().key("tag").value("value").build();
        tagFilterResourceProvider = mock(TagFilterResourceProvider.class);

        namespaceConfig = mock(NamespaceConfig.class);
        expect(namespaceConfig.getName()).andReturn("lambda").anyTimes();
        ScrapeConfigProvider scrapeConfigProvider = mock(ScrapeConfigProvider.class);
        expect(scrapeConfigProvider.getScrapeConfig()).andReturn(
                ScrapeConfig.builder()
                        .regions(ImmutableSet.of("region1"))
                        .namespaces(ImmutableList.of(namespaceConfig))
                        .build()
        ).anyTimes();

        AWSClientProvider awsClientProvider = mock(AWSClientProvider.class);
        expect(awsClientProvider.getLambdaClient("region1")).andReturn(lambdaClient).anyTimes();

        testClass = new LambdaEventSourceExporter(scrapeConfigProvider, awsClientProvider,
                metricNameUtil, gaugeExporter, resourceMapper, tagFilterResourceProvider) {
            @Override
            Instant now() {
                return now;
            }
        };
    }

    @Test
    public void exportEventSourceMappings() {
        Resource fn1Resource = Resource.builder()
                .arn("fn1_arn")
                .region("region1")
                .name("fn1")
                .type(LambdaFunction)
                .tags(ImmutableList.of(tag))
                .build();

        Resource queueResource = Resource.builder()
                .type(SQSQueue)
                .arn("queue_arn")
                .name("queue")
                .region("region1")
                .build();

        Resource fn2Resource = Resource.builder()
                .type(LambdaFunction)
                .arn("fn2_arn")
                .name("fn2")
                .region("region1")
                .build();

        Resource dynamoTableResource = Resource.builder().type(DynamoDBTable)
                .arn("table_arn")
                .name("table")
                .region("region1")
                .build();

        expect(tagFilterResourceProvider.getFilteredResources("region1", namespaceConfig))
                .andReturn(ImmutableSet.of(fn1Resource, fn2Resource));

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

        expect(metricNameUtil.getMetricPrefix("AWS/Lambda")).andReturn("aws_lambda").anyTimes();

        expect(resourceMapper.map("fn1_arn")).andReturn(Optional.of(fn1Resource));
        expect(resourceMapper.map("queue_arn")).andReturn(Optional.of(queueResource));
        expect(metricNameUtil.getResourceTagLabels(fn1Resource)).andReturn(ImmutableMap.of("tag", "value"));

        expect(resourceMapper.map("fn2_arn")).andReturn(Optional.of(fn2Resource));
        expect(resourceMapper.map("table_arn")).andReturn(Optional.of(dynamoTableResource));
        expect(metricNameUtil.getResourceTagLabels(fn2Resource)).andReturn(ImmutableMap.of());

        String help = "Metric with lambda event source information";
        gaugeExporter.exportMetric("aws_lambda_event_source", help,
                ImmutableSortedMap.of(
                        "region", "region1",
                        "event_source_name", "queue",
                        "event_source_type", "SQSQueue",
                        "lambda_function", "fn1",
                        "tag", "value"
                ),
                now, 1.0D);

        gaugeExporter.exportMetric("aws_lambda_event_source", help,
                ImmutableSortedMap.of(
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

    @Test
    public void exportEventSourceMappings_Exception() {
        expect(tagFilterResourceProvider.getFilteredResources("region1", namespaceConfig))
                .andThrow(new RuntimeException());

        replayAll();
        testClass.run();
        verifyAll();
    }
}
