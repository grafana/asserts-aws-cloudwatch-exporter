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
import ai.asserts.aws.cloudwatch.prometheus.GaugeExporter;
import ai.asserts.aws.resource.Resource;
import ai.asserts.aws.resource.TagFilterResourceProvider;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import org.easymock.EasyMockSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.AccountLimit;
import software.amazon.awssdk.services.lambda.model.GetAccountSettingsResponse;
import software.amazon.awssdk.services.lambda.model.ListProvisionedConcurrencyConfigsRequest;
import software.amazon.awssdk.services.lambda.model.ListProvisionedConcurrencyConfigsResponse;
import software.amazon.awssdk.services.lambda.model.ProvisionedConcurrencyConfigListItem;

import java.time.Instant;
import java.util.Collections;
import java.util.Optional;

import static ai.asserts.aws.MetricNameUtil.SCRAPE_LATENCY_METRIC;
import static org.easymock.EasyMock.anyObject;
import static org.easymock.EasyMock.eq;
import static org.easymock.EasyMock.expect;


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
    private Instant now;
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

        now = Instant.now();
        testClass = new LambdaCapacityExporter() {
            @Override
            Instant now() {
                return now;
            }
        };
        testClass.setScrapeConfigProvider(scrapeConfigProvider);
        testClass.setAwsClientProvider(awsClientProvider);
        testClass.setMetricNameUtil(metricNameUtil);
        testClass.setGaugeExporter(gaugeExporter);
        testClass.setFunctionScraper(functionScraper);
        testClass.setTagFilterResourceProvider(tagFilterResourceProvider);

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

        expect(lambdaFunction.getName()).andReturn("fn1").times(2);
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
        gaugeExporter.exportMetric("timeout", "", ImmutableMap.of(
                "region", "region1", "function_name", "fn1"
        ), now, 120.0D);
        gaugeExporter.exportMetric(eq(SCRAPE_LATENCY_METRIC), anyObject(), anyObject(), anyObject(), anyObject());
        gaugeExporter.exportMetric("available", "", ImmutableMap.of(
                "region", "region1", "function_name", "fn1", "d_executed_version", "1"
        ), now, 100.0D);
        gaugeExporter.exportMetric("requested", "", ImmutableMap.of(
                "region", "region1", "function_name", "fn1", "d_executed_version", "1"
        ), now, 20.0D);
        gaugeExporter.exportMetric("allocated", "", ImmutableMap.of(
                "region", "region1", "function_name", "fn1", "d_executed_version", "1"
        ), now, 10.0D);


        expectAccountSettings("region2");

        expect(tagFilterResourceProvider.getFilteredResources("region2", namespaceConfig))
                .andReturn(ImmutableSet.of(resource));
        expect(resource.getArn()).andReturn("arn2");
        resource.addTagLabels(ImmutableMap.of(), metricNameUtil);

        expect(lambdaFunction.getName()).andReturn("fn2").times(2);
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
        gaugeExporter.exportMetric("timeout", "", ImmutableMap.of(
                "region", "region2", "function_name", "fn2"
        ), now, 60.0D);
        gaugeExporter.exportMetric(eq(SCRAPE_LATENCY_METRIC), anyObject(), anyObject(), anyObject(), anyObject());
        gaugeExporter.exportMetric("available", "", ImmutableMap.of(
                "region", "region2", "function_name", "fn2", "d_resource", "green"
        ), now, 100.0D);
        gaugeExporter.exportMetric("requested", "", ImmutableMap.of(
                "region", "region2", "function_name", "fn2", "d_resource", "green"
        ), now, 30.0D);
        gaugeExporter.exportMetric("allocated", "", ImmutableMap.of(
                "region", "region2", "function_name", "fn2", "d_resource", "green"
        ), now, 20.0D);

        replayAll();
        testClass.run();
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

        expect(lambdaFunction.getName()).andReturn("fn1").times(2);
        expect(lambdaFunction.getTimeoutSeconds()).andReturn(60);
        expect(lambdaClient.listProvisionedConcurrencyConfigs(ListProvisionedConcurrencyConfigsRequest.builder()
                .functionName("fn1")
                .build()))
                .andReturn(ListProvisionedConcurrencyConfigsResponse.builder()
                        .provisionedConcurrencyConfigs(Collections.emptyList())
                        .build());
        gaugeExporter.exportMetric("timeout", "", ImmutableMap.of(
                "region", "region1", "function_name", "fn1"
        ), now, 60.0D);
        gaugeExporter.exportMetric(eq(SCRAPE_LATENCY_METRIC), anyObject(), anyObject(), anyObject(), anyObject());
        replayAll();
        testClass.run();
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

        replayAll();
        testClass.run();
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
        gaugeExporter.exportMetric("limit", "", ImmutableMap.of(
                "region", region, "type", "concurrent_executions"), now, 10.0D
        );
        gaugeExporter.exportMetric("limit", "", ImmutableMap.of(
                "region", region, "type", "unreserved_concurrent_executions"), now, 20.0D
        );
    }
}
