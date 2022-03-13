/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.exporter;

import ai.asserts.aws.AWSClientProvider;
import ai.asserts.aws.MetricNameUtil;
import ai.asserts.aws.RateLimiter;
import ai.asserts.aws.cloudwatch.config.NamespaceConfig;
import ai.asserts.aws.cloudwatch.config.ScrapeConfig;
import ai.asserts.aws.cloudwatch.config.ScrapeConfigProvider;
import ai.asserts.aws.lambda.LambdaFunctionScraper;
import ai.asserts.aws.resource.Resource;
import ai.asserts.aws.resource.ResourceTagHelper;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;
import io.prometheus.client.Collector;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.GetAccountSettingsResponse;
import software.amazon.awssdk.services.lambda.model.GetFunctionConcurrencyRequest;
import software.amazon.awssdk.services.lambda.model.GetFunctionConcurrencyResponse;
import software.amazon.awssdk.services.lambda.model.ListProvisionedConcurrencyConfigsRequest;
import software.amazon.awssdk.services.lambda.model.ListProvisionedConcurrencyConfigsResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

import static ai.asserts.aws.MetricNameUtil.SCRAPE_ACCOUNT_ID_LABEL;
import static ai.asserts.aws.MetricNameUtil.SCRAPE_NAMESPACE_LABEL;
import static ai.asserts.aws.MetricNameUtil.SCRAPE_OPERATION_LABEL;
import static ai.asserts.aws.MetricNameUtil.SCRAPE_REGION_LABEL;
import static ai.asserts.aws.cloudwatch.model.CWNamespace.lambda;

@Component
@Slf4j
public class LambdaCapacityExporter extends Collector implements MetricProvider {
    private final ScrapeConfigProvider scrapeConfigProvider;
    private final AWSClientProvider awsClientProvider;
    private final MetricNameUtil metricNameUtil;
    private final MetricSampleBuilder sampleBuilder;
    private final LambdaFunctionScraper functionScraper;
    private final ResourceTagHelper resourceTagHelper;
    private final RateLimiter rateLimiter;
    private volatile List<MetricFamilySamples> cache;

    public LambdaCapacityExporter(ScrapeConfigProvider scrapeConfigProvider, AWSClientProvider awsClientProvider,
                                  MetricNameUtil metricNameUtil,
                                  MetricSampleBuilder sampleBuilder, LambdaFunctionScraper functionScraper,
                                  ResourceTagHelper resourceTagHelper,
                                  RateLimiter rateLimiter) {
        this.scrapeConfigProvider = scrapeConfigProvider;
        this.awsClientProvider = awsClientProvider;
        this.metricNameUtil = metricNameUtil;
        this.sampleBuilder = sampleBuilder;
        this.functionScraper = functionScraper;
        this.resourceTagHelper = resourceTagHelper;
        this.rateLimiter = rateLimiter;
        this.cache = new ArrayList<>();
    }

    public List<MetricFamilySamples> collect() {
        return cache;
    }

    @Override
    public void update() {
        cache = getMetrics();
    }

    private List<MetricFamilySamples> getMetrics() {
        ScrapeConfig scrapeConfig = scrapeConfigProvider.getScrapeConfig();
        Optional<NamespaceConfig> optional = scrapeConfig.getLambdaConfig();
        String availableMetric = metricNameUtil.getLambdaMetric("available_concurrency");
        String requestedMetric = metricNameUtil.getLambdaMetric("requested_concurrency");
        String allocatedMetric = metricNameUtil.getLambdaMetric("allocated_concurrency");
        String reservedMetric = metricNameUtil.getLambdaMetric("reserved_concurrency");
        String timeoutMetric = metricNameUtil.getLambdaMetric("timeout_seconds");
        String memoryLimit = metricNameUtil.getLambdaMetric("memory_limit_mb");
        String accountLimitMetric = metricNameUtil.getLambdaMetric("account_limit");

        Map<String, List<MetricFamilySamples.Sample>> samples = new TreeMap<>();

        optional.ifPresent(lambdaConfig -> functionScraper.getFunctions().forEach((region, functions) -> {
            log.info(" - Getting Lambda account and provisioned concurrency for region {}", region);
            try (LambdaClient lambdaClient = awsClientProvider.getLambdaClient(region, scrapeConfig.getAssumeRole())) {
                GetAccountSettingsResponse accountSettings = rateLimiter.doWithRateLimit(
                        "LambdaClient/getAccountSettings",
                        ImmutableSortedMap.of(
                                SCRAPE_REGION_LABEL, region,
                                SCRAPE_OPERATION_LABEL, "getAccountSettings",
                                SCRAPE_NAMESPACE_LABEL, "AWS/Lambda"
                        ),
                        lambdaClient::getAccountSettings);

                MetricFamilySamples.Sample sample = sampleBuilder.buildSingleSample(accountLimitMetric, ImmutableMap.of(
                        "region", region, "cw_namespace", lambda.getNormalizedNamespace(),
                        "type", "concurrent_executions"
                ), accountSettings.accountLimit().concurrentExecutions() * 1.0D);
                samples.computeIfAbsent(accountLimitMetric, k -> new ArrayList<>()).add(sample);

                sample = sampleBuilder.buildSingleSample(accountLimitMetric, ImmutableMap.of(
                        "region", region, "cw_namespace", lambda.getNormalizedNamespace(),
                        "type", "unreserved_concurrent_executions"
                ), accountSettings.accountLimit().unreservedConcurrentExecutions() * 1.0D);
                samples.computeIfAbsent(accountLimitMetric, k -> new ArrayList<>()).add(sample);

                Set<Resource> fnResources = resourceTagHelper.getFilteredResources(region, lambdaConfig);
                functions.forEach((functionArn, lambdaFunction) -> {
                    GetFunctionConcurrencyResponse fCResponse = rateLimiter.doWithRateLimit("LambdaClient/getFunctionConcurrency",
                            ImmutableSortedMap.of(
                                    SCRAPE_REGION_LABEL, region,
                                    SCRAPE_OPERATION_LABEL, "getFunctionConcurrency",
                                    SCRAPE_NAMESPACE_LABEL, "AWS/Lambda"
                            ), () -> lambdaClient.getFunctionConcurrency(GetFunctionConcurrencyRequest.builder()
                                    .functionName(lambdaFunction.getArn())
                                    .build()));

                    if (fCResponse.reservedConcurrentExecutions() != null) {
                        MetricFamilySamples.Sample reserved = sampleBuilder.buildSingleSample(reservedMetric, ImmutableMap.of(
                                "region", region, "cw_namespace", lambda.getNormalizedNamespace(),
                                "d_function_name", lambdaFunction.getName(), "job", lambdaFunction.getName(),
                                SCRAPE_ACCOUNT_ID_LABEL, lambdaFunction.getAccount()
                        ), fCResponse.reservedConcurrentExecutions().doubleValue());
                        samples.computeIfAbsent(reservedMetric, k -> new ArrayList<>()).add(reserved);
                    }

                    Optional<Resource> fnResourceOpt = fnResources.stream()
                            .filter(resource -> functionArn.equals(resource.getArn()))
                            .findFirst();

                    Map<String, String> labels = new TreeMap<>();
                    fnResourceOpt.ifPresent(fnResource -> fnResource.addEnvLabel(labels, metricNameUtil));

                    labels.put("region", region);
                    labels.put("cw_namespace", lambda.getNormalizedNamespace());
                    labels.put("d_function_name", lambdaFunction.getName());
                    labels.put("job", lambdaFunction.getName());
                    labels.put(SCRAPE_ACCOUNT_ID_LABEL, lambdaFunction.getAccount());

                    // Export timeout
                    double timeout = lambdaFunction.getTimeoutSeconds() * 1.0D;
                    samples.computeIfAbsent(timeoutMetric, k -> new ArrayList<>())
                            .add(sampleBuilder.buildSingleSample(timeoutMetric, labels, timeout));

                    samples.computeIfAbsent(memoryLimit, k -> new ArrayList<>())
                            .add(sampleBuilder.buildSingleSample(memoryLimit, labels, lambdaFunction.getMemoryMB() * 1.0D));


                    ListProvisionedConcurrencyConfigsRequest request = ListProvisionedConcurrencyConfigsRequest
                            .builder()
                            .functionName(lambdaFunction.getName())
                            .build();

                    ListProvisionedConcurrencyConfigsResponse response =
                            rateLimiter.doWithRateLimit(
                                    "LambdaClient/listProvisionedConcurrencyConfigs",
                                    ImmutableSortedMap.of(
                                            SCRAPE_REGION_LABEL, region,
                                            SCRAPE_OPERATION_LABEL, "listProvisionedConcurrencyConfigs",
                                            SCRAPE_NAMESPACE_LABEL, "AWS/Lambda"
                                    ),
                                    () -> lambdaClient.listProvisionedConcurrencyConfigs(request));

                    if (response.hasProvisionedConcurrencyConfigs()) {
                        response.provisionedConcurrencyConfigs().forEach(config -> {
                            // Capacity is always provisioned at alias or version level
                            String[] parts = config.functionArn().split(":");
                            String level = Character.isDigit(parts[parts.length - 1].charAt(0)) ?
                                    "d_executed_version" : "d_resource";
                            labels.put(level, parts[parts.length - 1]);

                            Integer available = config.availableProvisionedConcurrentExecutions();
                            samples.computeIfAbsent(availableMetric, k -> new ArrayList<>())
                                    .add(sampleBuilder.buildSingleSample(
                                            availableMetric, labels, available.doubleValue()));

                            Integer requested = config.requestedProvisionedConcurrentExecutions();
                            samples.computeIfAbsent(requestedMetric, k -> new ArrayList<>())
                                    .add(sampleBuilder.buildSingleSample(
                                            requestedMetric, labels, requested.doubleValue()));

                            Integer allocated = config.allocatedProvisionedConcurrentExecutions();
                            samples.computeIfAbsent(allocatedMetric, k -> new ArrayList<>())
                                    .add(sampleBuilder.buildSingleSample(
                                            allocatedMetric, labels, allocated.doubleValue()));
                        });
                    }
                });
            } catch (Exception e) {
                log.error("Failed to get lambda provisioned capacity for region " + region, e);
            }
        }));
        return samples.values().

                stream()
                .

                        map(sampleBuilder::buildFamily)
                .

                        collect(Collectors.toList());
    }

    private ImmutableSortedMap<String, String> operationLabels(String region) {
        return ImmutableSortedMap.of(
                SCRAPE_NAMESPACE_LABEL, "AWS/Lambda",
                SCRAPE_REGION_LABEL, region,
                SCRAPE_OPERATION_LABEL, "list_provisioned_concurrency_configs"
        );
    }
}
