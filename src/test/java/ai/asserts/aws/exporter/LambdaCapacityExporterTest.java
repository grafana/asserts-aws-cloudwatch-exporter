/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.exporter;

import ai.asserts.aws.AWSClientProvider;
import ai.asserts.aws.MetricNameUtil;
import ai.asserts.aws.RateLimiter;
import ai.asserts.aws.cloudwatch.config.NamespaceConfig;
import ai.asserts.aws.cloudwatch.config.ScrapeConfig;
import ai.asserts.aws.cloudwatch.config.ScrapeConfigProvider;
import ai.asserts.aws.lambda.LambdaFunction;
import ai.asserts.aws.lambda.LambdaFunctionScraper;
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
import software.amazon.awssdk.services.lambda.model.GetFunctionConcurrencyRequest;
import software.amazon.awssdk.services.lambda.model.GetFunctionConcurrencyResponse;
import software.amazon.awssdk.services.lambda.model.ListProvisionedConcurrencyConfigsRequest;
import software.amazon.awssdk.services.lambda.model.ListProvisionedConcurrencyConfigsResponse;
import software.amazon.awssdk.services.lambda.model.ProvisionedConcurrencyConfigListItem;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.SortedMap;

import static ai.asserts.aws.MetricNameUtil.SCRAPE_ACCOUNT_ID_LABEL;
import static ai.asserts.aws.MetricNameUtil.SCRAPE_LATENCY_METRIC;
import static org.easymock.EasyMock.anyLong;
import static org.easymock.EasyMock.anyObject;
import static org.easymock.EasyMock.anyString;
import static org.easymock.EasyMock.eq;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.expectLastCall;
import static org.junit.jupiter.api.Assertions.assertEquals;


public class LambdaCapacityExporterTest extends EasyMockSupport {
    private NamespaceConfig namespaceConfig;
    private AWSClientProvider awsClientProvider;
    private LambdaClient lambdaClient;
    private MetricNameUtil metricNameUtil;
    private BasicMetricCollector metricCollector;
    private LambdaFunctionScraper functionScraper;
    private TagFilterResourceProvider tagFilterResourceProvider;
    private Resource resource;
    private MetricSampleBuilder sampleBuilder;
    private Sample sample;
    private Collector.MetricFamilySamples familySamples;
    private LambdaCapacityExporter testClass;

    private LambdaFunction fn1;
    private Map<String, String> fn1Labels;
    private Map<String, String> fn1VersionLabels;

    private LambdaFunction fn2;
    private Map<String, String> fn2Labels;
    private Map<String, String> fn2ResourceLabels;

    @BeforeEach
    public void setup() {
        ScrapeConfigProvider scrapeConfigProvider = mock(ScrapeConfigProvider.class);
        ScrapeConfig scrapeConfig = mock(ScrapeConfig.class);
        namespaceConfig = mock(NamespaceConfig.class);
        awsClientProvider = mock(AWSClientProvider.class);
        lambdaClient = mock(LambdaClient.class);
        metricNameUtil = mock(MetricNameUtil.class);
        metricCollector = mock(BasicMetricCollector.class);
        functionScraper = mock(LambdaFunctionScraper.class);
        tagFilterResourceProvider = mock(TagFilterResourceProvider.class);
        resource = mock(Resource.class);
        sampleBuilder = mock(MetricSampleBuilder.class);
        sample = mock(Sample.class);
        familySamples = mock(Collector.MetricFamilySamples.class);

        resetAll();

        testClass = new LambdaCapacityExporter(scrapeConfigProvider, awsClientProvider, metricNameUtil,
                sampleBuilder, functionScraper, tagFilterResourceProvider, new RateLimiter(metricCollector));
        expect(scrapeConfigProvider.getScrapeConfig()).andReturn(scrapeConfig);
        expect(scrapeConfig.getLambdaConfig()).andReturn(Optional.of(namespaceConfig));
        expect(metricNameUtil.getLambdaMetric("available_concurrency")).andReturn("available");
        expect(metricNameUtil.getLambdaMetric("requested_concurrency")).andReturn("requested");
        expect(metricNameUtil.getLambdaMetric("allocated_concurrency")).andReturn("allocated");
        expect(metricNameUtil.getLambdaMetric("timeout_seconds")).andReturn("timeout");
        expect(metricNameUtil.getLambdaMetric("memory_limit_mb")).andReturn("memory_limit");
        expect(metricNameUtil.getLambdaMetric("account_limit")).andReturn("limit");
        expect(metricNameUtil.getLambdaMetric("reserved_concurrency")).andReturn("reserved");

        fn1 = LambdaFunction.builder()
                .account("account1")
                .region("region1")
                .arn("arn1")
                .name("fn1")
                .memoryMB(128)
                .timeoutSeconds(120)
                .build();
        fn1Labels = ImmutableMap.of(
                "region", "region1", "d_function_name", "fn1", "job", "fn1",
                "cw_namespace", "AWS/Lambda", SCRAPE_ACCOUNT_ID_LABEL, "account1"
        );

        fn1VersionLabels = new HashMap<>(ImmutableMap.of(
                "region", "region1", "d_function_name", "fn1", "d_executed_version", "1",
                "job", "fn1", "cw_namespace", "AWS/Lambda"
        ));
        fn1VersionLabels.put(SCRAPE_ACCOUNT_ID_LABEL, "account1");

        fn2 = LambdaFunction.builder()
                .account("account2")
                .region("region2")
                .arn("arn2")
                .name("fn2")
                .memoryMB(128)
                .timeoutSeconds(60)
                .build();
        fn2Labels = ImmutableMap.of(
                "region", "region2", "d_function_name", "fn2", "job", "fn2",
                "cw_namespace", "AWS/Lambda", SCRAPE_ACCOUNT_ID_LABEL, "account2"
        );

        fn2ResourceLabels = new HashMap<>(ImmutableMap.of(
                "region", "region2", "d_function_name", "fn2", "d_resource", "green",
                "job", "fn2", "cw_namespace", "AWS/Lambda"
        ));
        fn2ResourceLabels.put(SCRAPE_ACCOUNT_ID_LABEL, "account2");
    }

    @Test
    public void run() {
        expect(functionScraper.getFunctions()).andReturn(ImmutableMap.of(
                "region1", ImmutableMap.of("arn1", fn1),
                "region2", ImmutableMap.of("arn2", fn2)
        ));

        expectAccountSettings("region1");

        expect(tagFilterResourceProvider.getFilteredResources("region1", namespaceConfig))
                .andReturn(ImmutableSet.of(resource));
        expect(resource.getArn()).andReturn("arn1");
        resource.addTagLabels(ImmutableMap.of(), metricNameUtil);

        expect(lambdaClient.getFunctionConcurrency(GetFunctionConcurrencyRequest.builder()
                .functionName("arn1")
                .build()))
                .andReturn(GetFunctionConcurrencyResponse.builder()
                        .reservedConcurrentExecutions(100)
                        .build());
        metricCollector.recordLatency(anyObject(), anyObject(), anyLong());
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
        metricCollector.recordLatency(anyObject(), anyObject(), anyLong());
        expect(sampleBuilder.buildSingleSample("timeout", fn1Labels, 120.0D)).andReturn(sample);
        expect(sampleBuilder.buildSingleSample("memory_limit", fn1Labels, 128.0D)).andReturn(sample);
        expect(sampleBuilder.buildSingleSample("reserved", fn1Labels, 100.0D)).andReturn(sample);
        expect(sampleBuilder.buildSingleSample("available", fn1VersionLabels, 100.0D)).andReturn(sample);
        expect(sampleBuilder.buildSingleSample("requested", fn1VersionLabels, 20.0D)).andReturn(sample);
        expect(sampleBuilder.buildSingleSample("allocated", fn1VersionLabels, 10.0D)).andReturn(sample);
        lambdaClient.close();

        expectAccountSettings("region2");

        expect(tagFilterResourceProvider.getFilteredResources("region2", namespaceConfig))
                .andReturn(ImmutableSet.of(resource));
        expect(resource.getArn()).andReturn("arn2");
        resource.addTagLabels(ImmutableMap.of(), metricNameUtil);

        expect(lambdaClient.getFunctionConcurrency(GetFunctionConcurrencyRequest.builder()
                .functionName("arn2")
                .build()))
                .andReturn(GetFunctionConcurrencyResponse.builder()
                        .reservedConcurrentExecutions(null)
                        .build());
        metricCollector.recordLatency(anyObject(), anyObject(), anyLong());
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
        metricCollector.recordLatency(anyObject(), anyObject(), anyLong());

        expect(sampleBuilder.buildSingleSample("timeout", fn2Labels, 60.0D)).andReturn(sample);
        expect(sampleBuilder.buildSingleSample("memory_limit", fn2Labels, 128.0D)).andReturn(sample);
        expect(sampleBuilder.buildSingleSample("available", fn2ResourceLabels, 100.0D)).andReturn(sample);
        expect(sampleBuilder.buildSingleSample("requested", fn2ResourceLabels, 30.0D)).andReturn(sample);
        expect(sampleBuilder.buildSingleSample("allocated", fn2ResourceLabels, 20.0D)).andReturn(sample);
        lambdaClient.close();

        expect(sampleBuilder.buildFamily(ImmutableList.of(sample))).andReturn(familySamples);
        expect(sampleBuilder.buildFamily(ImmutableList.of(sample, sample))).andReturn(familySamples).times(5);
        expect(sampleBuilder.buildFamily(ImmutableList.of(sample, sample, sample, sample))).andReturn(familySamples);

        replayAll();
        testClass.update();
        testClass.collect();
        assertEquals(ImmutableList.of(familySamples, familySamples, familySamples, familySamples, familySamples,
                familySamples, familySamples),
                testClass.collect());
        verifyAll();
    }

    @Test
    public void run_noProvisionedCapacity() {
        expect(functionScraper.getFunctions()).andReturn(ImmutableMap.of(
                "region1", ImmutableMap.of("arn1", fn1)
        ));
        expectAccountSettings("region1");

        expect(tagFilterResourceProvider.getFilteredResources("region1", namespaceConfig))
                .andReturn(ImmutableSet.of(resource));
        expect(resource.getArn()).andReturn("arn1");
        resource.addTagLabels(Collections.emptyMap(), metricNameUtil);

        expect(lambdaClient.getFunctionConcurrency(GetFunctionConcurrencyRequest.builder()
                .functionName("arn1")
                .build()))
                .andReturn(GetFunctionConcurrencyResponse.builder()
                        .reservedConcurrentExecutions(null)
                        .build());
        expect(lambdaClient.listProvisionedConcurrencyConfigs(ListProvisionedConcurrencyConfigsRequest.builder()
                .functionName("fn1")
                .build()))
                .andReturn(ListProvisionedConcurrencyConfigsResponse.builder()
                        .provisionedConcurrencyConfigs(Collections.emptyList())
                        .build());

        expect(sampleBuilder.buildSingleSample("timeout", fn1Labels, 120.0)).andReturn(sample);
        expect(sampleBuilder.buildSingleSample("memory_limit", fn1Labels, 128.0D)).andReturn(sample);

        lambdaClient.close();

        expect(sampleBuilder.buildFamily(ImmutableList.of(sample, sample))).andReturn(familySamples);
        expect(sampleBuilder.buildFamily(ImmutableList.of(sample))).andReturn(familySamples).times(2);
        metricCollector.recordLatency(anyString(), anyObject(), anyLong());
        expectLastCall().times(2);
        replayAll();
        testClass.update();
        testClass.collect();
        assertEquals(ImmutableList.of(familySamples, familySamples, familySamples), testClass.collect());
        verifyAll();
    }

    @Test
    public void run_Exception() {
        expect(functionScraper.getFunctions()).andReturn(ImmutableMap.of(
                "region1", ImmutableMap.of("arn1", fn1)
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
        metricCollector.recordLatency(eq(SCRAPE_LATENCY_METRIC), anyObject(SortedMap.class), anyLong());
        expect(sampleBuilder.buildSingleSample("limit", ImmutableMap.of(
                "region", region, "type", "concurrent_executions", "cw_namespace", "AWS/Lambda"), 10.0D
        )).andReturn(sample);
        expect(sampleBuilder.buildSingleSample("limit", ImmutableMap.of(
                "region", region, "type", "unreserved_concurrent_executions", "cw_namespace", "AWS/Lambda"), 20.0D
        )).andReturn(sample);
    }
}
