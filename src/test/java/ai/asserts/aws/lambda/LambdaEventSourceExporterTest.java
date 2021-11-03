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
import ai.asserts.aws.cloudwatch.metrics.MetricSampleBuilder;
import ai.asserts.aws.resource.Resource;
import ai.asserts.aws.resource.ResourceMapper;
import ai.asserts.aws.resource.TagFilterResourceProvider;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import io.prometheus.client.Collector;
import io.prometheus.client.Collector.MetricFamilySamples.Sample;
import org.easymock.EasyMockSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.EventSourceMappingConfiguration;
import software.amazon.awssdk.services.lambda.model.ListEventSourceMappingsRequest;
import software.amazon.awssdk.services.lambda.model.ListEventSourceMappingsResponse;

import java.util.Optional;

import static org.easymock.EasyMock.expect;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class LambdaEventSourceExporterTest extends EasyMockSupport {
    private LambdaClient lambdaClient;
    private MetricNameUtil metricNameUtil;
    private ResourceMapper resourceMapper;
    private NamespaceConfig namespaceConfig;
    private TagFilterResourceProvider tagFilterResourceProvider;
    private LambdaEventSourceExporter testClass;
    private Resource fnResource;
    private Resource sourceResource;
    private MetricSampleBuilder sampleBuilder;
    private Sample sample;
    private Collector.MetricFamilySamples familySamples;

    @BeforeEach
    public void setup() {
        metricNameUtil = mock(MetricNameUtil.class);
        lambdaClient = mock(LambdaClient.class);
        resourceMapper = mock(ResourceMapper.class);
        fnResource = mock(Resource.class);
        sourceResource = mock(Resource.class);
        tagFilterResourceProvider = mock(TagFilterResourceProvider.class);
        sampleBuilder = mock(MetricSampleBuilder.class);
        sample = mock(Sample.class);
        familySamples = mock(Collector.MetricFamilySamples.class);

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
                metricNameUtil, resourceMapper, tagFilterResourceProvider, sampleBuilder);
    }

    @Test
    public void exportEventSourceMappings() {
        ImmutableSortedMap<String, String> fn1Labels = ImmutableSortedMap.of(
                "region", "region1",
                "lambda_function", "fn1"
        );

        ImmutableSortedMap<String, String> fn2Labels = ImmutableSortedMap.of(
                "region", "region1",
                "lambda_function", "fn2"
        );

        ListEventSourceMappingsRequest request = ListEventSourceMappingsRequest.builder()
                .build();
        expect(lambdaClient.listEventSourceMappings(request)).andReturn(
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

        expect(tagFilterResourceProvider.getFilteredResources("region1", namespaceConfig))
                .andReturn(ImmutableSet.of(fnResource, fnResource));

        String help = "Metric with lambda event source information";

        expect(metricNameUtil.getMetricPrefix("AWS/Lambda")).andReturn("aws_lambda").anyTimes();

        expect(fnResource.getName()).andReturn("fn1");
        expect(fnResource.getArn()).andReturn("fn1_arn");
        expect(resourceMapper.map("fn1_arn")).andReturn(Optional.of(fnResource)).times(2);
        expect(resourceMapper.map("queue_arn")).andReturn(Optional.of(sourceResource));
        fnResource.addTagLabels(fn1Labels, metricNameUtil);
        sourceResource.addLabels(fn1Labels, "event_source");

        expect(sampleBuilder.buildSingleSample("aws_lambda_event_source", fn1Labels, 1.0D))
                .andReturn(sample);

        expect(fnResource.getName()).andReturn("fn2");
        expect(fnResource.getArn()).andReturn("fn2_arn");
        expect(resourceMapper.map("fn2_arn")).andReturn(Optional.of(fnResource)).times(2);
        expect(resourceMapper.map("table_arn")).andReturn(Optional.of(sourceResource));
        fnResource.addTagLabels(fn2Labels, metricNameUtil);
        sourceResource.addLabels(fn2Labels, "event_source");
        expect(sampleBuilder.buildSingleSample("aws_lambda_event_source",
                fn2Labels,
                1.0D)).andReturn(sample);
        lambdaClient.close();

        expect(sampleBuilder.buildFamily(ImmutableList.of(sample, sample))).andReturn(familySamples);

        replayAll();
        testClass.update();
        assertEquals(ImmutableList.of(familySamples), testClass.collect());
        verifyAll();
    }

    @Test
    public void exportEventSourceMappings_Exception() {
        ListEventSourceMappingsRequest request = ListEventSourceMappingsRequest.builder()
                .build();
        expect(lambdaClient.listEventSourceMappings(request)).andThrow(new RuntimeException());
        lambdaClient.close();
        replayAll();
        testClass.update();
        testClass.collect();
        verifyAll();
    }
}
