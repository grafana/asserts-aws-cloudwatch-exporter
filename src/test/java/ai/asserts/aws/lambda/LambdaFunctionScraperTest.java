
package ai.asserts.aws.lambda;

import ai.asserts.aws.AWSClientProvider;
import ai.asserts.aws.RateLimiter;
import ai.asserts.aws.cloudwatch.config.NamespaceConfig;
import ai.asserts.aws.cloudwatch.config.ScrapeConfig;
import ai.asserts.aws.cloudwatch.config.ScrapeConfigProvider;
import ai.asserts.aws.exporter.BasicMetricCollector;
import ai.asserts.aws.resource.Resource;
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
import java.util.Optional;

import static org.easymock.EasyMock.anyLong;
import static org.easymock.EasyMock.anyObject;
import static org.easymock.EasyMock.anyString;
import static org.easymock.EasyMock.expect;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class LambdaFunctionScraperTest extends EasyMockSupport {
    private AWSClientProvider awsClientProvider;
    private LambdaClient lambdaClient;
    private LambdaFunctionBuilder lambdaFunctionBuilder;
    private BasicMetricCollector metricCollector;
    private TagFilterResourceProvider tagFilterResourceProvider;
    private NamespaceConfig namespaceConfig;
    private LambdaFunction lambdaFunction;
    private LambdaFunctionScraper lambdaFunctionScraper;
    private Resource fnResource;

    @BeforeEach
    public void setup() {
        awsClientProvider = mock(AWSClientProvider.class);
        lambdaClient = mock(LambdaClient.class);
        lambdaFunctionBuilder = mock(LambdaFunctionBuilder.class);
        metricCollector = mock(BasicMetricCollector.class);
        tagFilterResourceProvider = mock(TagFilterResourceProvider.class);
        namespaceConfig = mock(NamespaceConfig.class);
        lambdaFunction = mock(LambdaFunction.class);
        fnResource = mock(Resource.class);

        ScrapeConfigProvider scrapeConfigProvider = mock(ScrapeConfigProvider.class);
        expect(scrapeConfigProvider.getScrapeConfig()).andReturn(ScrapeConfig.builder()
                .regions(ImmutableSortedSet.of("region1", "region2"))
                .namespaces(ImmutableList.of(namespaceConfig))
                .build()).anyTimes();

        replayAll();
        lambdaFunctionScraper = new LambdaFunctionScraper(scrapeConfigProvider, awsClientProvider,
                metricCollector, tagFilterResourceProvider, lambdaFunctionBuilder, new RateLimiter());
        verifyAll();
        resetAll();
        expect(scrapeConfigProvider.getScrapeConfig()).andReturn(ScrapeConfig.builder()
                .regions(ImmutableSortedSet.of("region1", "region2"))
                .namespaces(ImmutableList.of(namespaceConfig))
                .build()).anyTimes();
        expect(namespaceConfig.getName()).andReturn("AWS/Lambda").anyTimes();
    }

    @Test
    public void getFunctions() {
        FunctionConfiguration fn1Config = FunctionConfiguration.builder()
                .functionArn("arn1")
                .functionName("fn1")
                .build();

        FunctionConfiguration fn2Config = FunctionConfiguration.builder()
                .functionArn("arn2")
                .functionName("fn2")
                .build();

        FunctionConfiguration fn3Config = FunctionConfiguration.builder()
                .functionArn("arn3")
                .functionName("fn3")
                .build();

        FunctionConfiguration fn4Config = FunctionConfiguration.builder()
                .functionArn("arn4")
                .functionName("fn4")
                .build();

        expect(awsClientProvider.getLambdaClient("region1")).andReturn(lambdaClient);

        expect(lambdaClient.listFunctions()).andReturn(ListFunctionsResponse.builder()
                .functions(ImmutableList.of(fn1Config, fn2Config)).build());
        expect(tagFilterResourceProvider.getFilteredResources("region1", namespaceConfig))
                .andReturn(ImmutableSet.of(fnResource));
        expect(fnResource.getArn()).andReturn("arn1").times(2);
        expect(lambdaFunctionBuilder.buildFunction("region1", fn1Config, Optional.of(fnResource)))
                .andReturn(lambdaFunction);
        expect(lambdaFunctionBuilder.buildFunction("region1", fn2Config, Optional.empty()))
                .andReturn(lambdaFunction);
        metricCollector.recordLatency(anyString(), anyObject(), anyLong());
        lambdaClient.close();

        expect(awsClientProvider.getLambdaClient("region2")).andReturn(lambdaClient);

        expect(lambdaClient.listFunctions()).andReturn(ListFunctionsResponse.builder()
                .functions(ImmutableList.of(fn3Config, fn4Config)).build());
        expect(tagFilterResourceProvider.getFilteredResources("region2", namespaceConfig))
                .andReturn(ImmutableSet.of(fnResource));
        expect(fnResource.getArn()).andReturn("arn3").times(2);
        expect(lambdaFunctionBuilder.buildFunction("region2", fn3Config, Optional.of(fnResource)))
                .andReturn(lambdaFunction);
        expect(lambdaFunctionBuilder.buildFunction("region2", fn4Config, Optional.empty()))
                .andReturn(lambdaFunction);
        metricCollector.recordLatency(anyString(), anyObject(), anyLong());
        lambdaClient.close();
        replayAll();

        assertEquals(ImmutableMap.of(
                "region1", ImmutableMap.of("arn1", lambdaFunction, "arn2", lambdaFunction),
                "region2", ImmutableMap.of("arn3", lambdaFunction, "arn4", lambdaFunction)
                ),
                lambdaFunctionScraper.getFunctions());

        verifyAll();
    }

    @Test
    public void getFunctions_Exception() {
        expect(awsClientProvider.getLambdaClient("region1")).andReturn(lambdaClient);
        expect(lambdaClient.listFunctions()).andThrow(new RuntimeException());
        lambdaClient.close();

        expect(awsClientProvider.getLambdaClient("region2")).andReturn(lambdaClient);
        expect(lambdaClient.listFunctions()).andThrow(new RuntimeException());
        lambdaClient.close();
        replayAll();
        Map<String, Map<String, LambdaFunction>> functionsByRegion = lambdaFunctionScraper.getFunctions();
        assertTrue(functionsByRegion.isEmpty());
        verifyAll();
    }
}
