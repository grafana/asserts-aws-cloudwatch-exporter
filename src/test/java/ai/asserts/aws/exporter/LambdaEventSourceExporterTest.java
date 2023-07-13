
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
import ai.asserts.aws.resource.Resource;
import ai.asserts.aws.resource.ResourceMapper;
import ai.asserts.aws.resource.ResourceTagHelper;
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

import static ai.asserts.aws.MetricNameUtil.SCRAPE_ACCOUNT_ID_LABEL;
import static org.easymock.EasyMock.anyInt;
import static org.easymock.EasyMock.anyLong;
import static org.easymock.EasyMock.anyObject;
import static org.easymock.EasyMock.anyString;
import static org.easymock.EasyMock.expect;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class LambdaEventSourceExporterTest extends EasyMockSupport {
    private AWSAccount accountRegion;
    private LambdaClient lambdaClient;
    private MetricNameUtil metricNameUtil;
    private ResourceMapper resourceMapper;
    private NamespaceConfig namespaceConfig;
    private ResourceTagHelper resourceTagHelper;
    private BasicMetricCollector metricCollector;
    private LambdaEventSourceExporter testClass;
    private Resource fnResource;
    private Resource sourceResource;
    private MetricSampleBuilder sampleBuilder;
    private Sample sample;
    private Collector.MetricFamilySamples familySamples;

    @BeforeEach
    public void setup() {
        accountRegion = new AWSAccount("acme", "account1", "", "",
                "role", ImmutableSet.of("region1"));
        AccountProvider accountProvider = mock(AccountProvider.class);
        metricNameUtil = mock(MetricNameUtil.class);
        lambdaClient = mock(LambdaClient.class);
        resourceMapper = mock(ResourceMapper.class);
        fnResource = mock(Resource.class);
        sourceResource = mock(Resource.class);
        resourceTagHelper = mock(ResourceTagHelper.class);
        sampleBuilder = mock(MetricSampleBuilder.class);
        sample = mock(Sample.class);
        familySamples = mock(Collector.MetricFamilySamples.class);
        metricCollector = mock(BasicMetricCollector.class);
        namespaceConfig = mock(NamespaceConfig.class);

        ScrapeConfigProvider scrapeConfigProvider = mock(ScrapeConfigProvider.class);
        expect(namespaceConfig.getName()).andReturn("AWS/Lambda").anyTimes();
        expect(scrapeConfigProvider.getScrapeConfig("acme")).andReturn(
                ScrapeConfig.builder()
                        .regions(ImmutableSet.of("region1"))
                        .namespaces(ImmutableList.of(namespaceConfig))
                        .build()
        ).anyTimes();

        expect(accountProvider.getAccounts()).andReturn(ImmutableSet.of(accountRegion));

        AWSClientProvider awsClientProvider = mock(AWSClientProvider.class);
        expect(awsClientProvider.getLambdaClient("region1", accountRegion)).andReturn(lambdaClient).anyTimes();

        AWSApiCallRateLimiter rateLimiter = new AWSApiCallRateLimiter(metricCollector, (account) -> "acme");
        testClass = new LambdaEventSourceExporter(accountProvider, scrapeConfigProvider, awsClientProvider,
                metricNameUtil, resourceMapper, resourceTagHelper, sampleBuilder,
                rateLimiter,
                new TaskExecutorUtil(new TestTaskThreadPool(), rateLimiter));
    }

    @Test
    public void exportEventSourceMappings() {
        ImmutableSortedMap<String, String> fn1Labels = ImmutableSortedMap.of(
                "region", "region1",
                "lambda_function", "fn1",
                SCRAPE_ACCOUNT_ID_LABEL, "account1"
        );

        ImmutableSortedMap<String, String> fn2Labels = ImmutableSortedMap.of(
                "region", "region1",
                "lambda_function", "fn2",
                SCRAPE_ACCOUNT_ID_LABEL, "account2"
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
        metricCollector.recordLatency(anyString(), anyObject(), anyLong());

        expect(resourceMapper.map("fn1_arn")).andReturn(Optional.of(fnResource)).times(2);
        expect(resourceMapper.map("queue_arn")).andReturn(Optional.of(sourceResource));
        expect(metricNameUtil.getMetricPrefix("AWS/Lambda")).andReturn("aws_lambda").anyTimes();

        expect(resourceTagHelper.getFilteredResources(accountRegion, "region1", namespaceConfig))
                .andReturn(ImmutableSet.of(fnResource, fnResource));

        expect(fnResource.getName()).andReturn("fn1");
        expect(fnResource.getArn()).andReturn("fn1_arn");
        expect(fnResource.getAccount()).andReturn("account1");
        fnResource.addEnvLabel(fn1Labels, metricNameUtil);
        sourceResource.addLabels(fn1Labels, "event_source");

        expect(sampleBuilder.buildSingleSample("aws_lambda_event_source", fn1Labels, 1.0D))
                .andReturn(Optional.of(sample));

        expect(fnResource.getName()).andReturn("fn2");
        expect(fnResource.getArn()).andReturn("fn2_arn");
        expect(fnResource.getAccount()).andReturn("account2");
        expect(resourceMapper.map("fn2_arn")).andReturn(Optional.of(fnResource)).times(2);
        expect(resourceMapper.map("table_arn")).andReturn(Optional.of(sourceResource));
        fnResource.addEnvLabel(fn2Labels, metricNameUtil);
        sourceResource.addLabels(fn2Labels, "event_source");
        expect(sampleBuilder.buildSingleSample("aws_lambda_event_source",
                fn2Labels,
                1.0D)).andReturn(Optional.of(sample));

        expect(sampleBuilder.buildFamily(ImmutableList.of(sample, sample))).andReturn(Optional.of(familySamples));

        replayAll();
        testClass.update();
        testClass.collect();
        assertEquals(ImmutableList.of(familySamples), testClass.collect());
        verifyAll();
    }

    @Test
    public void exportEventSourceMappings_Exception() {
        ListEventSourceMappingsRequest request = ListEventSourceMappingsRequest.builder()
                .build();
        expect(lambdaClient.listEventSourceMappings(request)).andThrow(new RuntimeException());
        metricCollector.recordCounterValue(anyString(), anyObject(), anyInt());
        metricCollector.recordLatency(anyString(), anyObject(), anyLong());
        replayAll();
        testClass.update();
        testClass.collect();
        verifyAll();
    }
}
