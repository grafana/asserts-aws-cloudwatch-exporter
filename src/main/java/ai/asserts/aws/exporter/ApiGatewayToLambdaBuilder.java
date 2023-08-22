/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.exporter;

import ai.asserts.aws.AWSClientProvider;
import ai.asserts.aws.MetricNameUtil;
import ai.asserts.aws.AWSApiCallRateLimiter;
import ai.asserts.aws.CollectionBuilderTask;
import ai.asserts.aws.TaskExecutorUtil;
import ai.asserts.aws.account.AWSAccount;
import ai.asserts.aws.account.AccountProvider;
import ai.asserts.aws.resource.Resource;
import ai.asserts.aws.resource.ResourceRelation;
import io.micrometer.core.instrument.util.StringUtils;
import io.prometheus.client.Collector;
import io.prometheus.client.Collector.MetricFamilySamples.Sample;
import io.prometheus.client.CollectorRegistry;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.apigateway.ApiGatewayClient;
import software.amazon.awssdk.services.apigateway.model.GetMethodRequest;
import software.amazon.awssdk.services.apigateway.model.GetMethodResponse;
import software.amazon.awssdk.services.apigateway.model.GetResourcesRequest;
import software.amazon.awssdk.services.apigateway.model.GetResourcesResponse;
import software.amazon.awssdk.services.apigateway.model.GetRestApisResponse;
import software.amazon.awssdk.services.apigateway.model.RestApi;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static ai.asserts.aws.MetricNameUtil.SCRAPE_ACCOUNT_ID_LABEL;
import static ai.asserts.aws.MetricNameUtil.SCRAPE_OPERATION_LABEL;
import static ai.asserts.aws.MetricNameUtil.SCRAPE_REGION_LABEL;
import static ai.asserts.aws.resource.ResourceType.ApiGateway;
import static ai.asserts.aws.resource.ResourceType.LambdaFunction;

@Component
@Slf4j
public class ApiGatewayToLambdaBuilder extends Collector
        implements MetricProvider, InitializingBean {
    private final AWSClientProvider awsClientProvider;
    private final AWSApiCallRateLimiter rateLimiter;
    private final AccountProvider accountProvider;
    private final MetricSampleBuilder metricSampleBuilder;
    private final CollectorRegistry collectorRegistry;
    private final MetricNameUtil metricNameUtil;
    private final TaskExecutorUtil taskExecutorUtil;
    private final Pattern LAMBDA_URI_PATTERN = Pattern.compile(
            "arn:aws:apigateway:(.+?):lambda:path/.+?/functions/arn:aws:lambda:(.+?):(.+?):function:(.+)/invocations");

    @Getter
    private volatile Set<ResourceRelation> lambdaIntegrations = new HashSet<>();
    private volatile List<MetricFamilySamples> apiResourceMetrics = new ArrayList<>();

    public ApiGatewayToLambdaBuilder(AWSClientProvider awsClientProvider,
                                     AWSApiCallRateLimiter rateLimiter, AccountProvider accountProvider,
                                     MetricSampleBuilder metricSampleBuilder,
                                     CollectorRegistry collectorRegistry, MetricNameUtil metricNameUtil,
                                     TaskExecutorUtil taskExecutorUtil) {
        this.awsClientProvider = awsClientProvider;
        this.rateLimiter = rateLimiter;
        this.accountProvider = accountProvider;
        this.metricSampleBuilder = metricSampleBuilder;
        this.collectorRegistry = collectorRegistry;
        this.metricNameUtil = metricNameUtil;
        this.taskExecutorUtil = taskExecutorUtil;
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        collectorRegistry.register(this);
    }

    @Override
    public List<MetricFamilySamples> collect() {
        return apiResourceMetrics;
    }

    public void update() {
        log.info("Exporting ApiGateway to Lambda relationship");
        Set<ResourceRelation> newIntegrations = new HashSet<>();
        List<MetricFamilySamples> newMetrics = new ArrayList<>();
        List<Sample> allSamples = new ArrayList<>();
        List<Future<List<Sample>>> futures = new ArrayList<>();
        for (AWSAccount accountRegion : accountProvider.getAccounts()) {
            accountRegion.getRegions().forEach(region ->
                    futures.add(taskExecutorUtil.executeAccountTask(accountRegion,
                            new CollectionBuilderTask<Sample>() {
                                @Override
                                public List<Sample> call() {
                                    return buildSamples(region, accountRegion, newIntegrations);
                                }
                            })));
        }
        taskExecutorUtil.awaitAll(futures, allSamples::addAll);

        if (allSamples.size() > 0) {
            metricSampleBuilder.buildFamily(allSamples).ifPresent(newMetrics::add);
        }

        lambdaIntegrations = newIntegrations;
        apiResourceMetrics = newMetrics;
    }

    private List<Sample> buildSamples(String region, AWSAccount accountRegion, Set<ResourceRelation> newIntegrations) {
        List<Sample> samples = new ArrayList<>();
        try {
            ApiGatewayClient client = awsClientProvider.getApiGatewayClient(region, accountRegion);
            SortedMap<String, String> labels = new TreeMap<>();
            String getRestApis = "ApiGatewayClient/getRestApis";
            labels.put(SCRAPE_OPERATION_LABEL, getRestApis);
            labels.put(SCRAPE_ACCOUNT_ID_LABEL, accountRegion.getAccountId());
            labels.put(SCRAPE_REGION_LABEL, region);
            GetRestApisResponse restApis =
                    rateLimiter.doWithRateLimit(getRestApis, labels, client::getRestApis);
            if (restApis.hasItems()) {
                restApis.items().forEach(restApi -> {
                    String getResources = "ResourceGroupsTaggingApiClient/getResources";
                    labels.put(SCRAPE_OPERATION_LABEL, getResources);
                    GetResourcesResponse resources =
                            rateLimiter.doWithRateLimit(getResources, labels,
                                    () -> client.getResources(GetResourcesRequest.builder()
                                            .restApiId(restApi.id())
                                            .build()));
                    if (resources.hasItems()) {
                        resources.items().forEach(resource -> {
                            captureIntegrations(client, newIntegrations,
                                    accountRegion.getAccountId(),
                                    labels, region, restApi, resource);
                            Map<String, String> apiResourceLabels = new TreeMap<>();
                            apiResourceLabels.put(SCRAPE_ACCOUNT_ID_LABEL,
                                    accountRegion.getAccountId());
                            apiResourceLabels.put(SCRAPE_REGION_LABEL, region);
                            apiResourceLabels.put("aws_resource_type", "AWS::ApiGateway::RestApi");
                            apiResourceLabels.put("namespace", "AWS/ApiGateway");
                            apiResourceLabels.put("name", restApi.name());
                            apiResourceLabels.put("id", restApi.id());
                            apiResourceLabels.put("job", restApi.name());
                            restApi.tags().forEach((key, value) -> apiResourceLabels.put(
                                    "tag_" + metricNameUtil.toSnakeCase(key), value));
                            metricSampleBuilder.buildSingleSample("aws_resource",
                                    apiResourceLabels, 1.0d).ifPresent(samples::add);
                        });
                    }
                });
            }
        } catch (Exception e) {
            log.error("Failed to discover lambda integrations for " + accountRegion, e);
        }
        return samples;
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
                if (resp != null && resp.methodIntegration() != null) {
                    String uri = resp.methodIntegration().uri();
                    if (StringUtils.isEmpty(uri)) {
                        return;
                    }
                    Matcher matcher = LAMBDA_URI_PATTERN.matcher(uri);
                    if (matcher.matches()) {
                        String tenant = taskExecutorUtil.getAccountDetails().getTenant();
                        ResourceRelation resourceRelation = ResourceRelation.builder()
                                .tenant(tenant)
                                .from(Resource.builder()
                                        .tenant(tenant)
                                        .type(ApiGateway)
                                        .name(restApi.name())
                                        .id(restApi.id())
                                        .region(region)
                                        .account(accountId)
                                        .build())
                                .to(Resource.builder()
                                        .tenant(tenant)
                                        .type(LambdaFunction)
                                        .name(matcher.group(4))
                                        .region(matcher.group(2))
                                        .account(matcher.group(3))
                                        .build())
                                .name("FORWARDS_TO")
                                .build();
                        newIntegrations.add(resourceRelation);
                    }
                }
            });
        }
    }
}
