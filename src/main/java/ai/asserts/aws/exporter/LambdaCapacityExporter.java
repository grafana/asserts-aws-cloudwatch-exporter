/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.exporter;

import ai.asserts.aws.AWSClientProvider;
import ai.asserts.aws.MetricNameUtil;
import ai.asserts.aws.AWSApiCallRateLimiter;
import ai.asserts.aws.ScrapeConfigProvider;
import ai.asserts.aws.SimpleTenantTask;
import ai.asserts.aws.TaskExecutorUtil;
import ai.asserts.aws.account.AWSAccount;
import ai.asserts.aws.account.AccountProvider;
import ai.asserts.aws.config.NamespaceConfig;
import ai.asserts.aws.config.ScrapeConfig;
import ai.asserts.aws.lambda.LambdaFunction;
import ai.asserts.aws.lambda.LambdaFunctionScraper;
import ai.asserts.aws.resource.Resource;
import ai.asserts.aws.resource.ResourceTagHelper;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;
import io.prometheus.client.Collector;
import io.prometheus.client.Collector.MetricFamilySamples.Sample;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.GetAccountSettingsResponse;
import software.amazon.awssdk.services.lambda.model.GetFunctionConcurrencyRequest;
import software.amazon.awssdk.services.lambda.model.GetFunctionConcurrencyResponse;
import software.amazon.awssdk.services.lambda.model.ListProvisionedConcurrencyConfigsRequest;
import software.amazon.awssdk.services.lambda.model.ListProvisionedConcurrencyConfigsResponse;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

import static ai.asserts.aws.MetricNameUtil.SCRAPE_ACCOUNT_ID_LABEL;
import static ai.asserts.aws.MetricNameUtil.SCRAPE_NAMESPACE_LABEL;
import static ai.asserts.aws.MetricNameUtil.SCRAPE_OPERATION_LABEL;
import static ai.asserts.aws.MetricNameUtil.SCRAPE_REGION_LABEL;
import static ai.asserts.aws.model.CWNamespace.lambda;

@Component
@Slf4j
public class LambdaCapacityExporter extends Collector implements MetricProvider {
    private final AccountProvider accountProvider;
    private final ScrapeConfigProvider scrapeConfigProvider;
    private final AWSClientProvider awsClientProvider;
    private final MetricNameUtil metricNameUtil;
    private final MetricSampleBuilder sampleBuilder;
    private final LambdaFunctionScraper functionScraper;
    private final ResourceTagHelper resourceTagHelper;
    private final AWSApiCallRateLimiter rateLimiter;
    private final TaskExecutorUtil taskExecutorUtil;
    private volatile List<MetricFamilySamples> cache;

    public LambdaCapacityExporter(AccountProvider accountProvider,
                                  ScrapeConfigProvider scrapeConfigProvider, AWSClientProvider awsClientProvider,
                                  MetricNameUtil metricNameUtil,
                                  MetricSampleBuilder sampleBuilder, LambdaFunctionScraper functionScraper,
                                  ResourceTagHelper resourceTagHelper,
                                  AWSApiCallRateLimiter rateLimiter, TaskExecutorUtil taskExecutorUtil) {
        this.accountProvider = accountProvider;
        this.scrapeConfigProvider = scrapeConfigProvider;
        this.awsClientProvider = awsClientProvider;
        this.metricNameUtil = metricNameUtil;
        this.sampleBuilder = sampleBuilder;
        this.functionScraper = functionScraper;
        this.resourceTagHelper = resourceTagHelper;
        this.rateLimiter = rateLimiter;
        this.taskExecutorUtil = taskExecutorUtil;
        this.cache = new ArrayList<>();
    }

    public List<MetricFamilySamples> collect() {
        return cache;
    }

    @Override
    public void update() {
        log.info("Updating Lambda Capacity");
        try {
            cache = getMetrics();
        } catch (Exception e) {
            log.error("Failed to discover Lambda function configurations", e);
        }
    }

    private List<MetricFamilySamples> getMetrics() {
        String availableMetric = metricNameUtil.getLambdaMetric("available_concurrency");
        String requestedMetric = metricNameUtil.getLambdaMetric("requested_concurrency");
        String allocatedMetric = metricNameUtil.getLambdaMetric("allocated_concurrency");
        String reservedMetric = metricNameUtil.getLambdaMetric("reserved_concurrency");
        String timeoutMetric = metricNameUtil.getLambdaMetric("timeout_seconds");
        String memoryLimit = metricNameUtil.getLambdaMetric("memory_limit_mb");
        String accountLimitMetric = metricNameUtil.getLambdaMetric("account_limit");

        Map<String, List<Sample>> allSamples = new TreeMap<>();
        Map<String, Map<String, Map<String, LambdaFunction>>> byAccountByRegion = functionScraper.getFunctions();
        List<Future<Map<String, List<Sample>>>> futures = new ArrayList<>();

        for (AWSAccount accountRegion : accountProvider.getAccounts()) {
            ScrapeConfig scrapeConfig = scrapeConfigProvider.getScrapeConfig(accountRegion.getTenant());
            Optional<NamespaceConfig> optional = scrapeConfig.getLambdaConfig();
            optional.ifPresent(lambdaConfig -> {
                String account = accountRegion.getAccountId();
                Map<String, Map<String, LambdaFunction>> byRegion = byAccountByRegion.getOrDefault(account,
                        Collections.emptyMap());
                byRegion.forEach((region, functions) -> futures.add(taskExecutorUtil.executeAccountTask(
                        accountRegion, new SimpleTenantTask<Map<String, List<Sample>>>() {
                            @Override
                            public Map<String, List<Sample>> call() {
                                return buildSamples(region, accountRegion, account, accountLimitMetric,
                                        lambdaConfig, functions, reservedMetric,
                                        timeoutMetric, memoryLimit, availableMetric, requestedMetric, allocatedMetric);
                            }
                        })));
            });
        }
        taskExecutorUtil.awaitAll(futures, (map) ->
                map.forEach((name, samples) ->
                        allSamples.computeIfAbsent(name, k -> new ArrayList<>()).addAll(samples)));
        return allSamples.values().stream()
                .map(sampleBuilder::buildFamily)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .collect(Collectors.toList());
    }

    private Map<String, List<Sample>> buildSamples(String region, AWSAccount accountRegion, String account,
                                                   String accountLimitMetric, NamespaceConfig lambdaConfig,
                                                   Map<String, LambdaFunction> functions, String reservedMetric,
                                                   String timeoutMetric, String memoryLimit, String availableMetric,
                                                   String requestedMetric, String allocatedMetric) {
        Map<String, List<Sample>> samples = new HashMap<>();

        log.info(" - Getting Lambda account and provisioned concurrency for region {}", region);
        try {
            LambdaClient lambdaClient = awsClientProvider.getLambdaClient(region, accountRegion);
            String getAccountSettings = "LambdaClient/getAccountSettings";
            GetAccountSettingsResponse accountSettings = rateLimiter.doWithRateLimit(
                    getAccountSettings,
                    ImmutableSortedMap.of(
                            SCRAPE_ACCOUNT_ID_LABEL, account,
                            SCRAPE_REGION_LABEL, region,
                            SCRAPE_OPERATION_LABEL, getAccountSettings,
                            SCRAPE_NAMESPACE_LABEL, "AWS/Lambda"
                    ),
                    lambdaClient::getAccountSettings);

            Optional<Sample> sampleOpt = sampleBuilder.buildSingleSample(accountLimitMetric,
                    ImmutableMap.of(
                            SCRAPE_ACCOUNT_ID_LABEL, account,
                            "region", region, "cw_namespace", lambda.getNormalizedNamespace(),
                            "type", "concurrent_executions"
                    ), accountSettings.accountLimit().concurrentExecutions() * 1.0D);
            sampleOpt.ifPresent(sample -> samples.computeIfAbsent(accountLimitMetric,
                    k -> new ArrayList<>()).add(sample));

            Optional<Sample> sample1Opt =
                    sampleBuilder.buildSingleSample(accountLimitMetric, ImmutableMap.of(
                            SCRAPE_ACCOUNT_ID_LABEL, account,
                            "region", region, "cw_namespace", lambda.getNormalizedNamespace(),
                            "type", "unreserved_concurrent_executions"
                    ), accountSettings.accountLimit().unreservedConcurrentExecutions() * 1.0D);
            sample1Opt.ifPresent(sample ->
                    samples.computeIfAbsent(accountLimitMetric, k -> new ArrayList<>()).add(sample));

            Set<Resource> fnResources =
                    resourceTagHelper.getFilteredResources(accountRegion, region, lambdaConfig);
            functions.forEach((functionArn, lambdaFunction) -> {
                String getFunctionConcurrency = "LambdaClient/getFunctionConcurrency";
                GetFunctionConcurrencyResponse fCResponse =
                        rateLimiter.doWithRateLimit(getFunctionConcurrency,
                                ImmutableSortedMap.of(
                                        SCRAPE_ACCOUNT_ID_LABEL, account,
                                        SCRAPE_REGION_LABEL, region,
                                        SCRAPE_OPERATION_LABEL, getFunctionConcurrency,
                                        SCRAPE_NAMESPACE_LABEL, "AWS/Lambda"
                                ), () -> lambdaClient.getFunctionConcurrency(
                                        GetFunctionConcurrencyRequest.builder()
                                                .functionName(lambdaFunction.getArn())
                                                .build()));

                if (fCResponse.reservedConcurrentExecutions() != null) {
                    Optional<Sample> reserved = sampleBuilder.buildSingleSample(reservedMetric,
                            new ImmutableMap.Builder<String, String>()
                                    .put("region", region)
                                    .put("cw_namespace", lambda.getNormalizedNamespace())
                                    .put("d_function_name", lambdaFunction.getName())
                                    .put("job", lambdaFunction.getName())
                                    .put(SCRAPE_ACCOUNT_ID_LABEL, lambdaFunction.getAccount())
                                    .build()
                            , fCResponse.reservedConcurrentExecutions().doubleValue());
                    reserved.ifPresent(sample -> samples.computeIfAbsent(reservedMetric,
                            k -> new ArrayList<>()).add(sample));
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
                sampleBuilder.buildSingleSample(timeoutMetric, labels, timeout).ifPresent(sample ->
                        samples.computeIfAbsent(timeoutMetric, k -> new ArrayList<>()).add(sample));

                sampleBuilder.buildSingleSample(memoryLimit, labels,
                        lambdaFunction.getMemoryMB() * 1.0D).ifPresent(sample ->
                        samples.computeIfAbsent(memoryLimit, k -> new ArrayList<>()).add(sample));


                ListProvisionedConcurrencyConfigsRequest request =
                        ListProvisionedConcurrencyConfigsRequest
                                .builder()
                                .functionName(lambdaFunction.getName())
                                .build();

                String provisionedConcurrency = "LambdaClient/listProvisionedConcurrencyConfigs";
                ListProvisionedConcurrencyConfigsResponse response =
                        rateLimiter.doWithRateLimit(
                                provisionedConcurrency,
                                ImmutableSortedMap.of(
                                        SCRAPE_ACCOUNT_ID_LABEL, account,
                                        SCRAPE_REGION_LABEL, region,
                                        SCRAPE_OPERATION_LABEL, provisionedConcurrency,
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
                        sampleBuilder.buildSingleSample(
                                availableMetric, labels, available.doubleValue()).ifPresent(sample ->
                                samples.computeIfAbsent(availableMetric, k -> new ArrayList<>())
                                        .add(sample));

                        Integer requested = config.requestedProvisionedConcurrentExecutions();
                        sampleBuilder.buildSingleSample(requestedMetric, labels,
                                requested.doubleValue()).ifPresent(sample ->
                                samples.computeIfAbsent(requestedMetric, k -> new ArrayList<>())
                                        .add(sample));


                        Integer allocated = config.allocatedProvisionedConcurrentExecutions();
                        sampleBuilder.buildSingleSample(allocatedMetric, labels,
                                        allocated.doubleValue())
                                .ifPresent(sample -> samples.computeIfAbsent(allocatedMetric,
                                        k -> new ArrayList<>()).add(sample));
                    });
                }
            });
        } catch (Exception e) {
            log.error("Failed to get lambda provisioned capacity for region " + region, e);
        }
        return samples;
    }
}
