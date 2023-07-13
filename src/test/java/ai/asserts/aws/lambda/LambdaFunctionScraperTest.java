
package ai.asserts.aws.lambda;

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
import ai.asserts.aws.exporter.BasicMetricCollector;
import ai.asserts.aws.exporter.ECSServiceDiscoveryExporter;
import ai.asserts.aws.exporter.MetricSampleBuilder;
import ai.asserts.aws.resource.Resource;
import ai.asserts.aws.resource.ResourceTagHelper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import io.prometheus.client.Collector.MetricFamilySamples;
import io.prometheus.client.Collector.MetricFamilySamples.Sample;
import org.easymock.EasyMockSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.FunctionConfiguration;
import software.amazon.awssdk.services.lambda.model.ListFunctionsResponse;

import java.util.Map;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;

import static ai.asserts.aws.MetricNameUtil.SCRAPE_ERROR_COUNT_METRIC;
import static ai.asserts.aws.MetricNameUtil.SCRAPE_LATENCY_METRIC;
import static org.easymock.EasyMock.anyLong;
import static org.easymock.EasyMock.anyObject;
import static org.easymock.EasyMock.anyString;
import static org.easymock.EasyMock.eq;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.expectLastCall;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SuppressWarnings("unchecked")
public class LambdaFunctionScraperTest extends EasyMockSupport {
    private AccountProvider accountProvider;
    private ScrapeConfigProvider scrapeConfigProvider;
    private AWSClientProvider awsClientProvider;
    private LambdaClient lambdaClient;
    private LambdaFunctionBuilder lambdaFunctionBuilder;
    private BasicMetricCollector metricCollector;
    private ResourceTagHelper resourceTagHelper;
    private NamespaceConfig namespaceConfig;
    private LambdaFunction lambdaFunction;
    private MetricNameUtil metricNameUtil;
    private MetricSampleBuilder metricSampleBuilder;
    private Sample sample;
    private MetricFamilySamples metricFamilySamples;
    private LambdaFunctionScraper lambdaFunctionScraper;
    private Resource fnResource;
    private AWSAccount accountRegion;
    private ECSServiceDiscoveryExporter ecsServiceDiscoveryExporter;

    @BeforeEach
    public void setup() {
        accountRegion = new AWSAccount("tenant", "account", "", "",
                "role", ImmutableSet.of("region1", "region2"));
        accountProvider = mock(AccountProvider.class);
        awsClientProvider = mock(AWSClientProvider.class);
        lambdaClient = mock(LambdaClient.class);
        lambdaFunctionBuilder = mock(LambdaFunctionBuilder.class);
        metricCollector = mock(BasicMetricCollector.class);
        resourceTagHelper = mock(ResourceTagHelper.class);
        namespaceConfig = mock(NamespaceConfig.class);
        lambdaFunction = mock(LambdaFunction.class);
        fnResource = mock(Resource.class);
        metricSampleBuilder = mock(MetricSampleBuilder.class);
        metricFamilySamples = mock(MetricFamilySamples.class);
        sample = mock(Sample.class);
        metricNameUtil = mock(MetricNameUtil.class);
        scrapeConfigProvider = mock(ScrapeConfigProvider.class);
        ecsServiceDiscoveryExporter = mock(ECSServiceDiscoveryExporter.class);
        expect(scrapeConfigProvider.getScrapeConfig("tenant")).andReturn(ScrapeConfig.builder()
                .regions(ImmutableSortedSet.of("region1", "region2"))
                .namespaces(ImmutableList.of(namespaceConfig))
                .build()).anyTimes();

        replayAll();
        lambdaFunctionScraper = new LambdaFunctionScraper(
                accountProvider,
                scrapeConfigProvider, awsClientProvider,
                resourceTagHelper, lambdaFunctionBuilder, new AWSApiCallRateLimiter(metricCollector, (account) -> "tenant"),
                metricSampleBuilder, metricNameUtil, ecsServiceDiscoveryExporter,
                new TaskExecutorUtil(new TestTaskThreadPool(),
                        new AWSApiCallRateLimiter(metricCollector, (account) -> "tenant")));
        verifyAll();
        resetAll();
        expect(scrapeConfigProvider.getScrapeConfig("tenant")).andReturn(ScrapeConfig.builder()
                .regions(ImmutableSortedSet.of("region1", "region2"))
                .namespaces(ImmutableList.of(namespaceConfig))
                .build()).anyTimes();
        expect(namespaceConfig.getName()).andReturn("AWS/Lambda").anyTimes();
    }

    @Test
    public void updateCollect() {
        Map<String, Map<String, Map<String, LambdaFunction>>> functions = new TreeMap<>();
        functions.put("account_id", ImmutableMap.of("region1", ImmutableMap.of("function",
                LambdaFunction.builder()
                        .tenant("acme")
                        .account("account_id")
                        .region("region1")
                        .name("function")
                        .resource(fnResource)
                        .build())));

        fnResource.addTagLabels(anyObject(), eq(metricNameUtil));
        fnResource.addEnvLabel(anyObject(), eq(metricNameUtil));

        expect(metricSampleBuilder.buildSingleSample(
                "aws_resource", new ImmutableMap.Builder<String, String>()
                        .put("tenant", "acme")
                        .put("account_id", "account_id")
                        .put("aws_resource_type", "AWS::Lambda::Function")
                        .put("namespace", "AWS/Lambda")
                        .put("region", "region1")
                        .put("name", "function")
                        .put("job", "function")
                        .put("id", "function")
                        .build(), 1.0D)).andReturn(Optional.of(sample));
        expect(metricSampleBuilder.buildFamily(ImmutableList.of(sample))).andReturn(Optional.of(metricFamilySamples));

        replayAll();

        lambdaFunctionScraper = new LambdaFunctionScraper(
                accountProvider,
                scrapeConfigProvider, awsClientProvider,
                resourceTagHelper, lambdaFunctionBuilder, new AWSApiCallRateLimiter(metricCollector, (account) -> "tenant"),
                metricSampleBuilder, metricNameUtil, ecsServiceDiscoveryExporter,
                new TaskExecutorUtil(new TestTaskThreadPool(),
                        new AWSApiCallRateLimiter(metricCollector, (account) -> "tenant"))) {
            @Override
            public Map<String, Map<String, Map<String, LambdaFunction>>> getFunctions() {
                return functions;
            }
        };

        lambdaFunctionScraper.update();
        assertEquals(ImmutableList.of(metricFamilySamples), lambdaFunctionScraper.collect());
        verifyAll();
    }

    @Test
    public void getFunctions() {
        expect(accountProvider.getAccounts()).andReturn(ImmutableSet.of(accountRegion));
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

        expect(awsClientProvider.getLambdaClient("region1", accountRegion)).andReturn(lambdaClient);

        expect(lambdaClient.listFunctions()).andReturn(ListFunctionsResponse.builder()
                .functions(ImmutableList.of(fn1Config, fn2Config)).build());
        expect(resourceTagHelper.getFilteredResources(accountRegion, "region1", namespaceConfig))
                .andReturn(ImmutableSet.of(fnResource));
        expect(fnResource.getArn()).andReturn("arn1").times(2);
        expect(lambdaFunctionBuilder.buildFunction("region1", fn1Config, Optional.of(fnResource)))
                .andReturn(lambdaFunction);
        expect(lambdaFunctionBuilder.buildFunction("region1", fn2Config, Optional.empty()))
                .andReturn(lambdaFunction);
        metricCollector.recordLatency(anyString(), anyObject(), anyLong());
        expect(awsClientProvider.getLambdaClient("region2", accountRegion)).andReturn(lambdaClient);

        expect(lambdaClient.listFunctions()).andReturn(ListFunctionsResponse.builder()
                .functions(ImmutableList.of(fn3Config, fn4Config)).build());
        expect(resourceTagHelper.getFilteredResources(accountRegion, "region2", namespaceConfig))
                .andReturn(ImmutableSet.of(fnResource));
        expect(fnResource.getArn()).andReturn("arn3").times(2);
        expect(lambdaFunctionBuilder.buildFunction("region2", fn3Config, Optional.of(fnResource)))
                .andReturn(lambdaFunction);
        expect(lambdaFunctionBuilder.buildFunction("region2", fn4Config, Optional.empty()))
                .andReturn(lambdaFunction);
        metricCollector.recordLatency(anyString(), anyObject(), anyLong());
        replayAll();

        assertEquals(ImmutableMap.of(
                        "account", ImmutableMap.of(
                                "region1", ImmutableMap.of("arn1", lambdaFunction, "arn2", lambdaFunction),
                                "region2", ImmutableMap.of("arn3", lambdaFunction, "arn4", lambdaFunction))
                ),
                lambdaFunctionScraper.getFunctions());

        verifyAll();
    }

    @Test
    public void getFunctions_Exception() {
        expect(accountProvider.getAccounts()).andReturn(ImmutableSet.of(accountRegion));
        expect(awsClientProvider.getLambdaClient("region1", accountRegion)).andReturn(lambdaClient);
        expect(lambdaClient.listFunctions()).andThrow(new RuntimeException());
        expect(awsClientProvider.getLambdaClient("region2", accountRegion)).andReturn(lambdaClient);
        expect(lambdaClient.listFunctions()).andThrow(new RuntimeException());
        metricCollector.recordCounterValue(eq(SCRAPE_ERROR_COUNT_METRIC), anyObject(SortedMap.class), eq(1));
        expectLastCall().times(2);
        metricCollector.recordLatency(eq(SCRAPE_LATENCY_METRIC), anyObject(SortedMap.class), anyLong());
        expectLastCall().times(2);
        replayAll();
        Map<String, Map<String, Map<String, LambdaFunction>>> functionsByRegion = lambdaFunctionScraper.getFunctions();
        assertTrue(functionsByRegion.isEmpty());
        verifyAll();
    }
}
