/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.exporter;

import ai.asserts.aws.AWSClientProvider;
import ai.asserts.aws.RateLimiter;
import ai.asserts.aws.cloudwatch.config.ScrapeConfigProvider;
import ai.asserts.aws.resource.Resource;
import ai.asserts.aws.resource.ResourceRelation;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.apigateway.ApiGatewayClient;
import software.amazon.awssdk.services.apigateway.model.GetMethodRequest;
import software.amazon.awssdk.services.apigateway.model.GetMethodResponse;
import software.amazon.awssdk.services.apigateway.model.GetResourcesRequest;
import software.amazon.awssdk.services.apigateway.model.GetResourcesResponse;
import software.amazon.awssdk.services.apigateway.model.GetRestApisResponse;
import software.amazon.awssdk.services.apigateway.model.RestApi;

import java.util.HashSet;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static ai.asserts.aws.MetricNameUtil.SCRAPE_ACCOUNT_ID_LABEL;
import static ai.asserts.aws.MetricNameUtil.SCRAPE_OPERATION_LABEL;
import static ai.asserts.aws.MetricNameUtil.SCRAPE_REGION_LABEL;
import static ai.asserts.aws.resource.ResourceType.APIGateway;
import static ai.asserts.aws.resource.ResourceType.LambdaFunction;

@Component
@Slf4j
public class ApiGatewayToLambdaBuilder {
    private final ScrapeConfigProvider scrapeConfigProvider;
    private final AWSClientProvider awsClientProvider;
    private final RateLimiter rateLimiter;
    private final AccountIDProvider accountIDProvider;
    private final Pattern LAMBDA_URI_PATTERN = Pattern.compile(
            "arn:aws:apigateway:(.+?):lambda:path/.+?/functions/arn:aws:lambda:(.+?):(.+?):function:(.+)/invocations");

    @Getter
    private volatile Set<ResourceRelation> lambdaIntegrations = new HashSet<>();

    public ApiGatewayToLambdaBuilder(ScrapeConfigProvider scrapeConfigProvider, AWSClientProvider awsClientProvider,
                                     RateLimiter rateLimiter, AccountIDProvider accountIDProvider) {
        this.scrapeConfigProvider = scrapeConfigProvider;
        this.awsClientProvider = awsClientProvider;
        this.rateLimiter = rateLimiter;
        this.accountIDProvider = accountIDProvider;
    }

    public void update() {
        Set<ResourceRelation> newIntegrations = new HashSet<>();
        try {
            String accountId = accountIDProvider.getAccountId();
            scrapeConfigProvider.getScrapeConfig().getRegions().forEach(region -> {
                ApiGatewayClient client = awsClientProvider.getApiGatewayClient(region);
                SortedMap<String, String> labels = new TreeMap<>();
                String getRestApis = "ApiGatewayClient/getRestApis";
                labels.put(SCRAPE_OPERATION_LABEL, getRestApis);
                labels.put(SCRAPE_ACCOUNT_ID_LABEL, accountId);
                labels.put(SCRAPE_REGION_LABEL, region);
                GetRestApisResponse restApis = rateLimiter.doWithRateLimit(getRestApis, labels, client::getRestApis);
                if (restApis.hasItems()) {
                    restApis.items().forEach(restApi -> {
                        String getResources = "getResources";
                        labels.put(SCRAPE_OPERATION_LABEL, getResources);
                        GetResourcesResponse resources = rateLimiter.doWithRateLimit(getResources, labels,
                                () -> client.getResources(GetResourcesRequest.builder()
                                        .restApiId(restApi.id())
                                        .build()));
                        if (resources.hasItems()) {
                            resources.items().forEach(resource ->
                                    captureIntegrations(client, newIntegrations, accountId, labels, region, restApi,
                                            resource));
                        }
                    });
                }
            });
        } catch (Exception e) {
            log.error("Failed to discover lambda integrations", e);
        }
        lambdaIntegrations = newIntegrations;
    }

    private void captureIntegrations(ApiGatewayClient client, Set<ResourceRelation> newIntegrations, String accountId,
                                     SortedMap<String, String> labels, String region, RestApi restApi,
                                     software.amazon.awssdk.services.apigateway.model.Resource apiResource) {
        if (apiResource.hasResourceMethods()) {
            apiResource.resourceMethods().forEach((name, method) -> {
                String api = "ApiGatewayClient/getMethod";
                labels.put(SCRAPE_OPERATION_LABEL, api);
                GetMethodRequest req = GetMethodRequest.builder()
                        .restApiId(restApi.id())
                        .resourceId(apiResource.id())
                        .httpMethod(name)
                        .build();
                GetMethodResponse resp = rateLimiter.doWithRateLimit(api, labels, () -> client.getMethod(req));
                String uri = resp.methodIntegration().uri();
                Matcher matcher = LAMBDA_URI_PATTERN.matcher(uri);
                if (matcher.matches()) {
                    ResourceRelation resourceRelation = ResourceRelation.builder()
                            .from(Resource.builder()
                                    .type(APIGateway)
                                    .name(restApi.name())
                                    .id(restApi.id())
                                    .region(region)
                                    .account(accountId)
                                    .build())
                            .to(Resource.builder()
                                    .type(LambdaFunction)
                                    .name(matcher.group(4))
                                    .region(matcher.group(2))
                                    .account(matcher.group(3))
                                    .build())
                            .name("FORWARDS_TO")
                            .build();
                    newIntegrations.add(resourceRelation);
                }
            });
        }
    }
}
