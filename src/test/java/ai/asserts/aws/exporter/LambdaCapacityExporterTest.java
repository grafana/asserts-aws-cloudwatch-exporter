/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.exporter;

import ai.asserts.aws.AWSClientProvider;
import ai.asserts.aws.MetricNameUtil;
import ai.asserts.aws.AWSApiCallRateLimiter;
import ai.asserts.aws.ScrapeConfigProvider;
import ai.asserts.aws.TaskExecutorUtil;
import ai.asserts.aws.TestTaskThreadPool;
import ai.asserts.aws.account.AWSAccount;
import ai.asserts.aws.account.AccountProvider;
import ai.asserts.aws.config.NamespaceConfig;
import ai.asserts.aws.config.ScrapeConfig;
import ai.asserts.aws.lambda.LambdaFunction;
import ai.asserts.aws.lambda.LambdaFunctionScraper;
import ai.asserts.aws.resource.Resource;
import ai.asserts.aws.resource.ResourceTagHelper;
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


@SuppressWarnings("unchecked")
public class LambdaCapacityExporterTest extends EasyMockSupport {
    private AccountProvider accountProvider;
    private AWSAccount account1;
    private AWSAccount account2;
    private NamespaceConfig namespaceConfig;
    private AWSClientProvider awsClientProvider;
    private LambdaClient lambdaClient;
    private MetricNameUtil metricNameUtil;
    private BasicMetricCollector metricCollector;
    private LambdaFunctionScraper functionScraper;
    private ResourceTagHelper resourceTagHelper;
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
        account1 = new AWSAccount("acme", "account1", "", "", "role",
                ImmutableSet.of("region"));
        account2 = new AWSAccount("acme", "account2", "", "", "role",
                ImmutableSet.of("region"));

        ScrapeConfig scrapeConfig = mock(ScrapeConfig.class);
        namespaceConfig = mock(NamespaceConfig.class);
        awsClientProvider = mock(AWSClientProvider.class);
        lambdaClient = mock(LambdaClient.class);
        metricNameUtil = mock(MetricNameUtil.class);
        metricCollector = mock(BasicMetricCollector.class);
        functionScraper = mock(LambdaFunctionScraper.class);
        resourceTagHelper = mock(ResourceTagHelper.class);
        resource = mock(Resource.class);
        sampleBuilder = mock(MetricSampleBuilder.class);
        sample = mock(Sample.class);
        familySamples = mock(Collector.MetricFamilySamples.class);
        accountProvider = mock(AccountProvider.class);
        ScrapeConfigProvider scrapeConfigProvider = mock(ScrapeConfigProvider.class);
        resetAll();

        AWSApiCallRateLimiter rateLimiter = new AWSApiCallRateLimiter(metricCollector, (account) -> "acme");
        testClass = new LambdaCapacityExporter(accountProvider,
                scrapeConfigProvider, awsClientProvider, metricNameUtil,
                sampleBuilder, functionScraper, resourceTagHelper, rateLimiter,
                new TaskExecutorUtil(new TestTaskThreadPool(), rateLimiter));

        expect(scrapeConfigProvider.getScrapeConfig("acme")).andReturn(scrapeConfig).anyTimes();
        expect(scrapeConfig.getLambdaConfig()).andReturn(Optional.of(namespaceConfig)).anyTimes();
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
        fn1Labels = new ImmutableMap.Builder<String, String>()
                .put("region", "region1")
                .put("d_function_name", "fn1")
                .put("job", "fn1")
                .put("cw_namespace", "AWS/Lambda")
                .put(SCRAPE_ACCOUNT_ID_LABEL, "account1")
                .build();

        fn1VersionLabels = new HashMap<>(fn1Labels);
        fn1VersionLabels.put("d_executed_version", "1");

        fn2 = LambdaFunction.builder()
                .account("account2")
                .region("region2")
                .arn("arn2")
                .name("fn2")
                .memoryMB(128)
                .timeoutSeconds(60)
                .build();

        fn2Labels = new ImmutableMap.Builder<String, String>()
                .put("region", "region2")
                .put("d_function_name", "fn2")
                .put("job", "fn2")
                .put("cw_namespace", "AWS/Lambda")
                .put(SCRAPE_ACCOUNT_ID_LABEL, "account2")
                .build();

        fn2ResourceLabels = new HashMap<>(fn2Labels);
        fn2ResourceLabels.put("d_resource", "green");
    }

    @Test
    public void run() {
        expect(accountProvider.getAccounts()).andReturn(ImmutableSet.of(account1, account2));
        expect(functionScraper.getFunctions()).andReturn(
                ImmutableMap.of(
                        "account1", ImmutableMap.of("region1", ImmutableMap.of("arn1", fn1)),
                        "account2", ImmutableMap.of("region2", ImmutableMap.of("arn2", fn2))
                )
        );

        expectAccountSettings(account1, "region1");

        expect(resourceTagHelper.getFilteredResources(account1, "region1", namespaceConfig))
                .andReturn(ImmutableSet.of(resource));
        expect(resource.getArn()).andReturn("arn1");
        resource.addEnvLabel(ImmutableMap.of(), metricNameUtil);

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
        expect(sampleBuilder.buildSingleSample("timeout", fn1Labels, 120.0D)).andReturn(Optional.of(sample));
        expect(sampleBuilder.buildSingleSample("memory_limit", fn1Labels, 128.0D)).andReturn(Optional.of(sample));
        expect(sampleBuilder.buildSingleSample("reserved", fn1Labels, 100.0D)).andReturn(Optional.of(sample));
        expect(sampleBuilder.buildSingleSample("available", fn1VersionLabels, 100.0D)).andReturn(Optional.of(sample));
        expect(sampleBuilder.buildSingleSample("requested", fn1VersionLabels, 20.0D)).andReturn(Optional.of(sample));
        expect(sampleBuilder.buildSingleSample("allocated", fn1VersionLabels, 10.0D)).andReturn(Optional.of(sample));

        expectAccountSettings(account2, "region2");

        expect(resourceTagHelper.getFilteredResources(account2, "region2", namespaceConfig))
                .andReturn(ImmutableSet.of(resource));
        expect(resource.getArn()).andReturn("arn2");
        resource.addEnvLabel(ImmutableMap.of(), metricNameUtil);

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

        expect(sampleBuilder.buildSingleSample("timeout", fn2Labels, 60.0D)).andReturn(Optional.of(sample));
        expect(sampleBuilder.buildSingleSample("memory_limit", fn2Labels, 128.0D)).andReturn(Optional.of(sample));
        expect(sampleBuilder.buildSingleSample("available", fn2ResourceLabels, 100.0D)).andReturn(Optional.of(sample));
        expect(sampleBuilder.buildSingleSample("requested", fn2ResourceLabels, 30.0D)).andReturn(Optional.of(sample));
        expect(sampleBuilder.buildSingleSample("allocated", fn2ResourceLabels, 20.0D)).andReturn(Optional.of(sample));

        expect(sampleBuilder.buildFamily(ImmutableList.of(sample))).andReturn(Optional.of(familySamples));
        expect(sampleBuilder.buildFamily(ImmutableList.of(sample, sample))).andReturn(Optional.of(familySamples))
                .times(5);
        expect(sampleBuilder.buildFamily(ImmutableList.of(sample, sample, sample, sample))).andReturn(
                Optional.of(familySamples));
        expectLastCall();

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
        expect(accountProvider.getAccounts()).andReturn(ImmutableSet.of(account1));
        expect(functionScraper.getFunctions()).andReturn(
                ImmutableMap.of(
                        "account1", ImmutableMap.of("region1", ImmutableMap.of("arn1", fn1))
                )
        );
        expectAccountSettings(account1, "region1");

        expect(resourceTagHelper.getFilteredResources(account1, "region1", namespaceConfig))
                .andReturn(ImmutableSet.of(resource));
        expect(resource.getArn()).andReturn("arn1");
        resource.addEnvLabel(Collections.emptyMap(), metricNameUtil);

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

        expect(sampleBuilder.buildSingleSample("timeout", fn1Labels, 120.0)).andReturn(Optional.of(sample));
        expect(sampleBuilder.buildSingleSample("memory_limit", fn1Labels, 128.0D)).andReturn(Optional.of(sample));

        expect(sampleBuilder.buildFamily(ImmutableList.of(sample, sample))).andReturn(Optional.of(familySamples));
        expect(sampleBuilder.buildFamily(ImmutableList.of(sample))).andReturn(Optional.of(familySamples)).times(2);
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
        expect(accountProvider.getAccounts()).andReturn(ImmutableSet.of(account1));
        expect(functionScraper.getFunctions()).andReturn(ImmutableMap.of("account1", ImmutableMap.of(
                "region1", ImmutableMap.of("arn1", fn1)
        )));
        expectAccountSettings(account1, "region1");
        expect(resourceTagHelper.getFilteredResources(account1, "region1", namespaceConfig))
                .andThrow(new RuntimeException());
        expect(sampleBuilder.buildFamily(ImmutableList.of(sample, sample))).andReturn(Optional.of(familySamples));
        replayAll();
        testClass.update();
        assertEquals(ImmutableList.of(familySamples), testClass.collect());
        verifyAll();
    }

    private void expectAccountSettings(AWSAccount account, String region) {
        expect(awsClientProvider.getLambdaClient(region, account)).andReturn(lambdaClient);
        expect(lambdaClient.getAccountSettings()).andReturn(GetAccountSettingsResponse.builder()
                .accountLimit(AccountLimit.builder()
                        .concurrentExecutions(10)
                        .unreservedConcurrentExecutions(20)
                        .build())
                .build());
        metricCollector.recordLatency(eq(SCRAPE_LATENCY_METRIC), anyObject(SortedMap.class), anyLong());
        expect(sampleBuilder.buildSingleSample("limit", ImmutableMap.of(
                "account_id", account.getAccountId(),
                "region", region, "type", "concurrent_executions", "cw_namespace", "AWS/Lambda"), 10.0D
        )).andReturn(Optional.of(sample));
        expect(sampleBuilder.buildSingleSample("limit", ImmutableMap.of(
                "account_id", account.getAccountId(),
                "region", region, "type", "unreserved_concurrent_executions", "cw_namespace", "AWS/Lambda"), 20.0D
        )).andReturn(Optional.of(sample));
    }
}
