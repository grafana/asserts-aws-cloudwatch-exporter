/*
 * Copyright © 2021
 * Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.lambda;

import ai.asserts.aws.AWSClientProvider;
import ai.asserts.aws.cloudwatch.config.NamespaceConfig;
import ai.asserts.aws.cloudwatch.config.ScrapeConfig;
import ai.asserts.aws.cloudwatch.config.ScrapeConfigProvider;
import ai.asserts.aws.cloudwatch.prometheus.GaugeExporter;
import ai.asserts.aws.resource.Resource;
import ai.asserts.aws.resource.ResourceType;
import ai.asserts.aws.resource.TagFilterResourceProvider;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import org.easymock.EasyMockSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.FunctionConfiguration;
import software.amazon.awssdk.services.lambda.model.ListFunctionsResponse;

import java.util.Map;

import static org.easymock.EasyMock.anyObject;
import static org.easymock.EasyMock.expect;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class LambdaFunctionScraperTest extends EasyMockSupport {
    private AWSClientProvider awsClientProvider;
    private LambdaClient lambdaClient;
    private GaugeExporter gaugeExporter;
    private TagFilterResourceProvider tagFilterResourceProvider;
    private NamespaceConfig namespaceConfig;
    private LambdaFunctionScraper lambdaFunctionScraper;

    @BeforeEach
    public void setup() {
        awsClientProvider = mock(AWSClientProvider.class);
        lambdaClient = mock(LambdaClient.class);
        gaugeExporter = mock(GaugeExporter.class);
        tagFilterResourceProvider = mock(TagFilterResourceProvider.class);
        namespaceConfig = mock(NamespaceConfig.class);

        ScrapeConfigProvider scrapeConfigProvider = mock(ScrapeConfigProvider.class);
        ScrapeConfig scrapeConfig = ScrapeConfig.builder()
                .regions(ImmutableSortedSet.of("region1", "region2"))
                .namespaces(ImmutableList.of(namespaceConfig))
                .build();
        expect(scrapeConfigProvider.getScrapeConfig()).andReturn(scrapeConfig).anyTimes();
        expect(namespaceConfig.getName()).andReturn("lambda").anyTimes();

        lambdaFunctionScraper = new LambdaFunctionScraper(scrapeConfigProvider, awsClientProvider,
                gaugeExporter, tagFilterResourceProvider);
    }

    @Test
    public void getFunctions() {
        expect(awsClientProvider.getLambdaClient("region1")).andReturn(lambdaClient);
        Resource fn1Resource = Resource.builder()
                .region("region1")
                .name("fn1")
                .type(ResourceType.LambdaFunction)
                .arn("arn1")
                .build();
        expect(tagFilterResourceProvider.getFilteredResources("region1", namespaceConfig))
                .andReturn(ImmutableSet.of(fn1Resource));

        expect(awsClientProvider.getLambdaClient("region2")).andReturn(lambdaClient);
        Resource fn3Resource = Resource.builder()
                .region("region1")
                .name("fn3")
                .type(ResourceType.LambdaFunction)
                .arn("arn3")
                .build();
        expect(tagFilterResourceProvider.getFilteredResources("region2", namespaceConfig))
                .andReturn(ImmutableSet.of(fn3Resource));

        expect(lambdaClient.listFunctions()).andReturn(ListFunctionsResponse.builder()
                .functions(ImmutableList.of(
                        FunctionConfiguration.builder()
                                .functionArn("arn1")
                                .functionName("fn1")
                                .build(),
                        FunctionConfiguration.builder()
                                .functionArn("arn2")
                                .functionName("fn2")
                                .build()
                        )
                ).build());
        gaugeExporter.exportMetric(anyObject(), anyObject(), anyObject(), anyObject(), anyObject());

        expect(lambdaClient.listFunctions()).andReturn(ListFunctionsResponse.builder()
                .functions(ImmutableList.of(
                        FunctionConfiguration.builder()
                                .functionArn("arn3")
                                .functionName("fn3")
                                .build(),
                        FunctionConfiguration.builder()
                                .functionArn("arn4")
                                .functionName("fn4")
                                .build()
                        )
                ).build());
        gaugeExporter.exportMetric(anyObject(), anyObject(), anyObject(), anyObject(), anyObject());

        replayAll();
        Map<String, Map<String, LambdaFunction>> functionsByRegion = lambdaFunctionScraper.getFunctions();

        assertTrue(functionsByRegion.containsKey("region1"));
        assertTrue(functionsByRegion.containsKey("region2"));

        assertEquals(
                ImmutableMap.of(
                        "arn1", LambdaFunction.builder()
                                .arn("arn1")
                                .name("fn1")
                                .region("region1")
                                .resource(fn1Resource)
                                .build()
                ),
                functionsByRegion.get("region1")
        );
        assertEquals(
                ImmutableMap.of(
                        "arn3", LambdaFunction.builder().arn("arn3")
                                .name("fn3")
                                .region("region2")
                                .resource(fn3Resource)
                                .build()
                ),
                functionsByRegion.get("region2")
        );

        verifyAll();
    }

    @Test
    public void getFunctions_Exception() {
        expect(awsClientProvider.getLambdaClient("region1")).andReturn(lambdaClient);
        expect(lambdaClient.listFunctions()).andThrow(new RuntimeException());

        expect(awsClientProvider.getLambdaClient("region2")).andReturn(lambdaClient);
        expect(lambdaClient.listFunctions()).andThrow(new RuntimeException());

        replayAll();
        Map<String, Map<String, LambdaFunction>> functionsByRegion = lambdaFunctionScraper.getFunctions();
        assertTrue(functionsByRegion.isEmpty());
        verifyAll();
    }
}
