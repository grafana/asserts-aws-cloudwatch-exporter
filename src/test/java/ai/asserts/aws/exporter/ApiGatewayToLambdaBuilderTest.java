/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.exporter;

import ai.asserts.aws.AWSClientProvider;
import ai.asserts.aws.MetricNameUtil;
import ai.asserts.aws.AWSApiCallRateLimiter;
import ai.asserts.aws.TaskExecutorUtil;
import ai.asserts.aws.TestTaskThreadPool;
import ai.asserts.aws.account.AWSAccount;
import ai.asserts.aws.account.AccountProvider;
import ai.asserts.aws.resource.ResourceRelation;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import io.prometheus.client.Collector.MetricFamilySamples;
import io.prometheus.client.Collector.MetricFamilySamples.Sample;
import io.prometheus.client.CollectorRegistry;
import org.easymock.EasyMockSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.apigateway.ApiGatewayClient;
import software.amazon.awssdk.services.apigateway.model.GetMethodRequest;
import software.amazon.awssdk.services.apigateway.model.GetMethodResponse;
import software.amazon.awssdk.services.apigateway.model.GetResourcesRequest;
import software.amazon.awssdk.services.apigateway.model.GetResourcesResponse;
import software.amazon.awssdk.services.apigateway.model.GetRestApisResponse;
import software.amazon.awssdk.services.apigateway.model.Integration;
import software.amazon.awssdk.services.apigateway.model.Method;
import software.amazon.awssdk.services.apigateway.model.Resource;
import software.amazon.awssdk.services.apigateway.model.RestApi;

import java.util.Optional;
import java.util.SortedMap;

import static ai.asserts.aws.MetricNameUtil.SCRAPE_LATENCY_METRIC;
import static ai.asserts.aws.resource.ResourceType.ApiGateway;
import static ai.asserts.aws.resource.ResourceType.LambdaFunction;
import static org.easymock.EasyMock.anyLong;
import static org.easymock.EasyMock.anyObject;
import static org.easymock.EasyMock.eq;
import static org.easymock.EasyMock.expect;
import static org.junit.jupiter.api.Assertions.assertEquals;

@SuppressWarnings("unchecked")
public class ApiGatewayToLambdaBuilderTest extends EasyMockSupport {
    private AWSAccount awsAccount;
    private AccountIDProvider accountIDProvider;
    private AWSClientProvider awsClientProvider;
    private AccountProvider accountProvider;
    private ApiGatewayClient apiGatewayClient;
    private BasicMetricCollector metricCollector;
    private MetricSampleBuilder metricSampleBuilder;
    private MetricFamilySamples metricFamilySamples;
    private Sample sample;
    private CollectorRegistry collectorRegistry;
    private MetricNameUtil metricNameUtil;
    private ApiGatewayToLambdaBuilder testClass;

    @BeforeEach
    public void setup() {
        accountIDProvider = mock(AccountIDProvider.class);
        awsClientProvider = mock(AWSClientProvider.class);
        accountProvider = mock(AccountProvider.class);
        apiGatewayClient = mock(ApiGatewayClient.class);
        metricCollector = mock(BasicMetricCollector.class);
        metricSampleBuilder = mock(MetricSampleBuilder.class);
        metricFamilySamples = mock(MetricFamilySamples.class);
        sample = mock(Sample.class);
        collectorRegistry = mock(CollectorRegistry.class);
        metricNameUtil = mock(MetricNameUtil.class);
        AWSApiCallRateLimiter rateLimiter = new AWSApiCallRateLimiter(metricCollector, (account) -> "acme");
        testClass = new ApiGatewayToLambdaBuilder(awsClientProvider, rateLimiter,
                accountProvider, metricSampleBuilder, collectorRegistry, metricNameUtil,
                new TaskExecutorUtil(new TestTaskThreadPool(), rateLimiter));
        awsAccount = new AWSAccount("acme", "account", "accessId",
                "secretKey", "role", ImmutableSet.of("region"));
    }

    @Test
    public void afterPropertiesSet() throws Exception {
        collectorRegistry.register(testClass);
        replayAll();
        testClass.afterPropertiesSet();
        verifyAll();
    }

    @Test
    void updateCollect() {
        expect(accountProvider.getAccounts()).andReturn(ImmutableSet.of(awsAccount));
        expect(awsClientProvider.getApiGatewayClient("region", awsAccount))
                .andReturn(apiGatewayClient).anyTimes();
        expect(accountIDProvider.getAccountId()).andReturn("account").anyTimes();
        expect(apiGatewayClient.getRestApis()).andReturn(GetRestApisResponse.builder()
                .items(RestApi.builder()
                        .id("rest-api-id")
                        .name("rest-api-name")
                        .tags(ImmutableMap.of("FooBar", "v"))
                        .build())
                .build());
        metricCollector.recordLatency(eq(SCRAPE_LATENCY_METRIC), anyObject(SortedMap.class), anyLong());
        expect(apiGatewayClient.getResources(GetResourcesRequest.builder()
                .restApiId("rest-api-id")
                .build())).andReturn(GetResourcesResponse.builder()
                .items(Resource.builder()
                        .id("resource-id")
                        .resourceMethods(ImmutableMap.of("GET", Method.builder().build()))
                        .build())
                .build());
        metricCollector.recordLatency(eq(SCRAPE_LATENCY_METRIC), anyObject(SortedMap.class), anyLong());
        String uri = "arn:aws:apigateway:us-west-2:lambda:path/2015-03-31/functions/" +
                "arn:aws:lambda:us-west-2:342994379019:function:Fn-With-Event-Invoke-Config/invocations";
        expect(apiGatewayClient.getMethod(GetMethodRequest.builder()
                .restApiId("rest-api-id")
                .resourceId("resource-id")
                .httpMethod("GET")
                .build())).andReturn(GetMethodResponse.builder()
                .methodIntegration(Integration.builder()
                        .uri(uri)
                        .build())
                .build());
        metricCollector.recordLatency(eq(SCRAPE_LATENCY_METRIC), anyObject(SortedMap.class), anyLong());
        expect(metricNameUtil.toSnakeCase("FooBar")).andReturn("foo_bar");
        expect(metricSampleBuilder.buildSingleSample("aws_resource",
                new ImmutableMap.Builder<String, String>()
                        .put("namespace", "AWS/ApiGateway")
                        .put("account_id", "account")
                        .put("region", "region")
                        .put("job", "rest-api-name")
                        .put("id", "rest-api-id")
                        .put("name", "rest-api-name")
                        .put("aws_resource_type", "AWS::ApiGateway::RestApi")
                        .put("tag_foo_bar", "v")
                        .build(), 1.0D)).andReturn(Optional.of(sample));

        expect(metricSampleBuilder.buildFamily(ImmutableList.of(sample))).andReturn(Optional.of(metricFamilySamples));

        replayAll();
        testClass.update();
        assertEquals(ImmutableSet.of(
                ResourceRelation.builder()
                        .tenant("acme")
                        .from(ai.asserts.aws.resource.Resource.builder()
                                .tenant("acme")
                                .account("account")
                                .region("region")
                                .type(ApiGateway)
                                .name("rest-api-name")
                                .id("rest-api-id")
                                .build())
                        .to(ai.asserts.aws.resource.Resource.builder()
                                .tenant("acme")
                                .account("account")
                                .type(LambdaFunction)
                                .region("us-west-2")
                                .account("342994379019")
                                .name("Fn-With-Event-Invoke-Config")
                                .build())
                        .name("FORWARDS_TO")
                        .build()
        ), testClass.getLambdaIntegrations());
        verifyAll();
    }

    @Test
    void updateCollect_methodIntegrationMissing() {
        expect(accountProvider.getAccounts()).andReturn(ImmutableSet.of(awsAccount));
        expect(awsClientProvider.getApiGatewayClient("region", awsAccount))
                .andReturn(apiGatewayClient).anyTimes();
        expect(accountIDProvider.getAccountId()).andReturn("account").anyTimes();
        expect(apiGatewayClient.getRestApis()).andReturn(GetRestApisResponse.builder()
                .items(RestApi.builder()
                        .id("rest-api-id")
                        .name("rest-api-name")
                        .tags(ImmutableMap.of("FooBar", "v"))
                        .build())
                .build());
        metricCollector.recordLatency(eq(SCRAPE_LATENCY_METRIC), anyObject(SortedMap.class), anyLong());
        expect(apiGatewayClient.getResources(GetResourcesRequest.builder()
                .restApiId("rest-api-id")
                .build())).andReturn(GetResourcesResponse.builder()
                .items(Resource.builder()
                        .id("resource-id")
                        .resourceMethods(ImmutableMap.of("GET", Method.builder().build()))
                        .build())
                .build());
        metricCollector.recordLatency(eq(SCRAPE_LATENCY_METRIC), anyObject(SortedMap.class), anyLong());
        expect(apiGatewayClient.getMethod(GetMethodRequest.builder()
                .restApiId("rest-api-id")
                .resourceId("resource-id")
                .httpMethod("GET")
                .build())).andReturn(GetMethodResponse.builder()
                .build());
        metricCollector.recordLatency(eq(SCRAPE_LATENCY_METRIC), anyObject(SortedMap.class), anyLong());
        expect(metricNameUtil.toSnakeCase("FooBar")).andReturn("foo_bar");
        expect(metricSampleBuilder.buildSingleSample("aws_resource",
                new ImmutableMap.Builder<String, String>()
                        .put("namespace", "AWS/ApiGateway")
                        .put("account_id", "account")
                        .put("region", "region")
                        .put("job", "rest-api-name")
                        .put("id", "rest-api-id")
                        .put("name", "rest-api-name")
                        .put("aws_resource_type", "AWS::ApiGateway::RestApi")
                        .put("tag_foo_bar", "v")
                        .build(), 1.0D)).andReturn(Optional.of(sample));

        expect(metricSampleBuilder.buildFamily(ImmutableList.of(sample))).andReturn(Optional.of(metricFamilySamples));

        replayAll();
        testClass.update();
        assertEquals(ImmutableSet.of(), testClass.getLambdaIntegrations());
        verifyAll();
    }
}
