/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.lambda;

import ai.asserts.aws.AWSClientProvider;
import ai.asserts.aws.MetricNameUtil;
import ai.asserts.aws.cloudwatch.config.NamespaceConfig;
import ai.asserts.aws.cloudwatch.config.ScrapeConfig;
import ai.asserts.aws.cloudwatch.config.ScrapeConfigProvider;
import ai.asserts.aws.cloudwatch.metrics.MetricSampleBuilder;
import ai.asserts.aws.cloudwatch.prometheus.GaugeExporter;
import ai.asserts.aws.resource.Resource;
import ai.asserts.aws.resource.TagFilterResourceProvider;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import io.prometheus.client.Collector;
import io.prometheus.client.Collector.MetricFamilySamples.Sample;
import org.easymock.EasyMockSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.AccountLimit;
import software.amazon.awssdk.services.lambda.model.GetAccountSettingsResponse;
import software.amazon.awssdk.services.lambda.model.ListProvisionedConcurrencyConfigsRequest;
import software.amazon.awssdk.services.lambda.model.ListProvisionedConcurrencyConfigsResponse;
import software.amazon.awssdk.services.lambda.model.ProvisionedConcurrencyConfigListItem;

import java.util.Collections;
import java.util.Optional;

import static ai.asserts.aws.MetricNameUtil.SCRAPE_LATENCY_METRIC;
import static org.easymock.EasyMock.anyObject;
import static org.easymock.EasyMock.eq;
import static org.easymock.EasyMock.expect;
import static org.junit.jupiter.api.Assertions.assertEquals;


public class LambdaCapacityExporterTest extends EasyMockSupport {
    private NamespaceConfig namespaceConfig;
    private AWSClientProvider awsClientProvider;
    private LambdaClient lambdaClient;
    private MetricNameUtil metricNameUtil;
    private GaugeExporter gaugeExporter;
    private LambdaFunctionScraper functionScraper;
    private TagFilterResourceProvider tagFilterResourceProvider;
    private LambdaFunction lambdaFunction;
    private Resource resource;
    private MetricSampleBuilder sampleBuilder;
    private Sample sample;
    private Collector.MetricFamilySamples familySamples;
    private LambdaCapacityExporter testClass;

    @BeforeEach
    public void setup() {
        ScrapeConfigProvider scrapeConfigProvider = mock(ScrapeConfigProvider.class);
        ScrapeConfig scrapeConfig = mock(ScrapeConfig.class);
        namespaceConfig = mock(NamespaceConfig.class);
        awsClientProvider = mock(AWSClientProvider.class);
        lambdaClient = mock(LambdaClient.class);
        metricNameUtil = mock(MetricNameUtil.class);
        gaugeExporter = mock(GaugeExporter.class);
        functionScraper = mock(LambdaFunctionScraper.class);
        tagFilterResourceProvider = mock(TagFilterResourceProvider.class);
        lambdaFunction = mock(LambdaFunction.class);
        resource = mock(Resource.class);
        sampleBuilder = mock(MetricSampleBuilder.class);
        sample = mock(Sample.class);
        familySamples = mock(Collector.MetricFamilySamples.class);

        testClass = new LambdaCapacityExporter(scrapeConfigProvider, awsClientProvider, metricNameUtil, gaugeExporter,
                sampleBuilder, functionScraper, tagFilterResourceProvider);
        expect(scrapeConfigProvider.getScrapeConfig()).andReturn(scrapeConfig);
        expect(scrapeConfig.getLambdaConfig()).andReturn(Optional.of(namespaceConfig));
        expect(metricNameUtil.getLambdaMetric("available_concurrency")).andReturn("available");
        expect(metricNameUtil.getLambdaMetric("requested_concurrency")).andReturn("requested");
        expect(metricNameUtil.getLambdaMetric("allocated_concurrency")).andReturn("allocated");
        expect(metricNameUtil.getLambdaMetric("timeout_seconds")).andReturn("timeout");
        expect(metricNameUtil.getLambdaMetric("account_limit")).andReturn("limit");
    }

    @Test
    public void run() {
        expect(functionScraper.getFunctions()).andReturn(ImmutableMap.of(
                "region1", ImmutableMap.of("arn1", lambdaFunction),
                "region2", ImmutableMap.of("arn2", lambdaFunction)
        ));

        expectAccountSettings("region1");

        expect(tagFilterResourceProvider.getFilteredResources("region1", namespaceConfig))
                .andReturn(ImmutableSet.of(resource));
        expect(resource.getArn()).andReturn("arn1");
        resource.addTagLabels(ImmutableMap.of(), metricNameUtil);

        expect(lambdaFunction.getName()).andReturn("fn1").times(3);
        expect(lambdaFunction.getTimeoutSeconds()).andReturn(120);
        expect(lambdaClient.listProvisionedConcurrencyConfigs(ListProvisionedConcurrencyConfigsRequest.builder()
                .functionName("fn1")
                .build()))
                .andReturn(ListProvisionedConcurrencyConfigsResponse.builder()
                        .provisionedConcurrencyConfigs(ProvisionedConcurrencyConfigListItem.builder()
                                .functionArn("arn:aws:lambda:us-west-2:123456789:function:fn1:1")
                                .requestedProvisionedConcurrentExecutions(20)
                                .allocatedProvisionedConcurrentExecutions(10)
                                .availableProvisionedConcurrentExecutions(100)
                                .build())
                        .build());
        expect(sampleBuilder.buildSingleSample("timeout", ImmutableMap.of(
                "region", "region1", "d_function_name", "fn1", "job", "fn1"
        ), 120.0D)).andReturn(sample);

        gaugeExporter.exportMetric(eq(SCRAPE_LATENCY_METRIC), anyObject(), anyObject(), anyObject(), anyObject());
        expect(sampleBuilder.buildSingleSample("available", ImmutableMap.of(
                "region", "region1", "d_function_name", "fn1", "d_executed_version", "1",
                "job", "fn1"
        ), 100.0D)).andReturn(sample);
        expect(sampleBuilder.buildSingleSample("requested", ImmutableMap.of(
                "region", "region1", "d_function_name", "fn1", "d_executed_version", "1",
                "job", "fn1"
        ), 20.0D)).andReturn(sample);
        expect(sampleBuilder.buildSingleSample("allocated", ImmutableMap.of(
                "region", "region1", "d_function_name", "fn1", "d_executed_version", "1",
                "job", "fn1"
        ), 10.0D)).andReturn(sample);
        lambdaClient.close();

        expectAccountSettings("region2");

        expect(tagFilterResourceProvider.getFilteredResources("region2", namespaceConfig))
                .andReturn(ImmutableSet.of(resource));
        expect(resource.getArn()).andReturn("arn2");
        resource.addTagLabels(ImmutableMap.of(), metricNameUtil);

        expect(lambdaFunction.getName()).andReturn("fn2").times(3);
        expect(lambdaFunction.getTimeoutSeconds()).andReturn(60);
        expect(lambdaClient.listProvisionedConcurrencyConfigs(ListProvisionedConcurrencyConfigsRequest.builder()
                .functionName("fn2")
                .build()))
                .andReturn(ListProvisionedConcurrencyConfigsResponse.builder()
                        .provisionedConcurrencyConfigs(ProvisionedConcurrencyConfigListItem.builder()
                                .functionArn("arn:aws:lambda:us-west-2:123456789:function:fn2:green")
                                .requestedProvisionedConcurrentExecutions(30)
                                .allocatedProvisionedConcurrentExecutions(20)
                                .availableProvisionedConcurrentExecutions(100)
                                .build())
                        .build());
        expect(sampleBuilder.buildSingleSample("timeout", ImmutableMap.of(
                "region", "region2", "d_function_name", "fn2", "job", "fn2"
        ), 60.0D)).andReturn(sample);

        gaugeExporter.exportMetric(eq(SCRAPE_LATENCY_METRIC), anyObject(), anyObject(), anyObject(), anyObject());
        expect(sampleBuilder.buildSingleSample("available", ImmutableMap.of(
                "region", "region2", "d_function_name", "fn2", "d_resource", "green",
                "job", "fn2"
        ), 100.0D)).andReturn(sample);
        expect(sampleBuilder.buildSingleSample("requested", ImmutableMap.of(
                "region", "region2", "d_function_name", "fn2", "d_resource", "green",
                "job", "fn2"
        ), 30.0D)).andReturn(sample);
        expect(sampleBuilder.buildSingleSample("allocated", ImmutableMap.of(
                "region", "region2", "d_function_name", "fn2", "d_resource", "green",
                "job", "fn2"
        ), 20.0D)).andReturn(sample);
        lambdaClient.close();

        expect(sampleBuilder.buildFamily(ImmutableList.of(sample, sample))).andReturn(familySamples).times(4);
        expect(sampleBuilder.buildFamily(ImmutableList.of(sample, sample, sample, sample))).andReturn(familySamples);

        replayAll();
        testClass.update();
        assertEquals(ImmutableList.of(familySamples, familySamples, familySamples, familySamples, familySamples),
                testClass.collect());
        verifyAll();
    }

    @Test
    public void run_noProvisionedCapacity() {
        expect(functionScraper.getFunctions()).andReturn(ImmutableMap.of(
                "region1", ImmutableMap.of("arn1", lambdaFunction)
        ));
        expectAccountSettings("region1");

        expect(tagFilterResourceProvider.getFilteredResources("region1", namespaceConfig))
                .andReturn(ImmutableSet.of(resource));
        expect(resource.getArn()).andReturn("arn1");
        resource.addTagLabels(Collections.emptyMap(), metricNameUtil);

        expect(lambdaFunction.getName()).andReturn("fn1").times(3);
        expect(lambdaFunction.getTimeoutSeconds()).andReturn(60);
        expect(lambdaClient.listProvisionedConcurrencyConfigs(ListProvisionedConcurrencyConfigsRequest.builder()
                .functionName("fn1")
                .build()))
                .andReturn(ListProvisionedConcurrencyConfigsResponse.builder()
                        .provisionedConcurrencyConfigs(Collections.emptyList())
                        .build());
        expect(sampleBuilder.buildSingleSample("timeout", ImmutableMap.of(
                "region", "region1", "d_function_name", "fn1", "job", "fn1"
        ), 60.0D)).andReturn(sample);
        gaugeExporter.exportMetric(eq(SCRAPE_LATENCY_METRIC), anyObject(), anyObject(), anyObject(), anyObject());
        lambdaClient.close();

        expect(sampleBuilder.buildFamily(ImmutableList.of(sample, sample))).andReturn(familySamples);
        expect(sampleBuilder.buildFamily(ImmutableList.of(sample))).andReturn(familySamples);

        replayAll();
        testClass.update();
        assertEquals(ImmutableList.of(familySamples, familySamples), testClass.collect());
        verifyAll();
    }

    @Test
    public void run_Exception() {
        expect(functionScraper.getFunctions()).andReturn(ImmutableMap.of(
                "region1", ImmutableMap.of("arn1", lambdaFunction)
        ));
        expectAccountSettings("region1");
        expect(tagFilterResourceProvider.getFilteredResources("region1", namespaceConfig))
                .andThrow(new RuntimeException());
        lambdaClient.close();
        expect(sampleBuilder.buildFamily(ImmutableList.of(sample, sample))).andReturn(familySamples);

        replayAll();
        testClass.update();
        assertEquals(ImmutableList.of(familySamples), testClass.collect());
        verifyAll();
    }

    private void expectAccountSettings(String region) {
        expect(awsClientProvider.getLambdaClient(region)).andReturn(lambdaClient);
        expect(lambdaClient.getAccountSettings()).andReturn(GetAccountSettingsResponse.builder()
                .accountLimit(AccountLimit.builder()
                        .concurrentExecutions(10)
                        .unreservedConcurrentExecutions(20)
                        .build())
                .build());
        expect(sampleBuilder.buildSingleSample("limit", ImmutableMap.of(
                "region", region, "type", "concurrent_executions"), 10.0D
        )).andReturn(sample);
        expect(sampleBuilder.buildSingleSample("limit", ImmutableMap.of(
                "region", region, "type", "unreserved_concurrent_executions"), 20.0D
        )).andReturn(sample);
    }
}
