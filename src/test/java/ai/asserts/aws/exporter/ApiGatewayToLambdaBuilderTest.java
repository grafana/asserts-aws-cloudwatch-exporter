/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.exporter;

import ai.asserts.aws.AWSClientProvider;
import ai.asserts.aws.RateLimiter;
import ai.asserts.aws.cloudwatch.config.ScrapeConfig;
import ai.asserts.aws.cloudwatch.config.ScrapeConfigProvider;
import ai.asserts.aws.resource.ResourceRelation;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
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

import java.util.SortedMap;

import static ai.asserts.aws.MetricNameUtil.SCRAPE_LATENCY_METRIC;
import static ai.asserts.aws.resource.ResourceType.ApiGateway;
import static ai.asserts.aws.resource.ResourceType.LambdaFunction;
import static org.easymock.EasyMock.anyLong;
import static org.easymock.EasyMock.anyObject;
import static org.easymock.EasyMock.eq;
import static org.easymock.EasyMock.expect;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class ApiGatewayToLambdaBuilderTest extends EasyMockSupport {
    private ApiGatewayClient apiGatewayClient;
    private BasicMetricCollector metricCollector;
    private ApiGatewayToLambdaBuilder testClass;

    @BeforeEach
    public void setup() {
        ScrapeConfigProvider scrapeConfigProvider = mock(ScrapeConfigProvider.class);
        ScrapeConfig scrapeConfig = mock(ScrapeConfig.class);

        AccountIDProvider accountIDProvider = mock(AccountIDProvider.class);
        AWSClientProvider awsClientProvider = mock(AWSClientProvider.class);
        apiGatewayClient = mock(ApiGatewayClient.class);
        metricCollector = mock(BasicMetricCollector.class);
        testClass = new ApiGatewayToLambdaBuilder(scrapeConfigProvider, awsClientProvider,
                new RateLimiter(metricCollector), accountIDProvider);
        expect(scrapeConfigProvider.getScrapeConfig()).andReturn(scrapeConfig).anyTimes();
        expect(scrapeConfig.getRegions()).andReturn(ImmutableSet.of("region"));
        expect(awsClientProvider.getApiGatewayClient("region")).andReturn(apiGatewayClient).anyTimes();
        expect(accountIDProvider.getAccountId()).andReturn("account").anyTimes();
    }

    @Test
    void update() {
        expect(apiGatewayClient.getRestApis()).andReturn(GetRestApisResponse.builder()
                .items(RestApi.builder()
                        .id("rest-api-id")
                        .name("rest-api-name")
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
        apiGatewayClient.close();
        replayAll();
        testClass.update();
        assertEquals(ImmutableSet.of(
                ResourceRelation.builder()
                        .from(ai.asserts.aws.resource.Resource.builder()
                                .account("account")
                                .region("region")
                                .type(ApiGateway)
                                .name("rest-api-name")
                                .id("rest-api-id")
                                .build())
                        .to(ai.asserts.aws.resource.Resource.builder()
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
}
