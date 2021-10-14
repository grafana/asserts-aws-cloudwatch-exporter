/*
 * Copyright © 2021
 * Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.lambda;

import ai.asserts.aws.AWSClientProvider;
import ai.asserts.aws.cloudwatch.config.NamespaceConfig;
import ai.asserts.aws.cloudwatch.config.ScrapeConfig;
import ai.asserts.aws.cloudwatch.config.ScrapeConfigProvider;
import ai.asserts.aws.cloudwatch.model.CWNamespace;
import ai.asserts.aws.cloudwatch.prometheus.GaugeExporter;
import ai.asserts.aws.resource.Resource;
import ai.asserts.aws.resource.TagFilterResourceProvider;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.FunctionConfiguration;
import software.amazon.awssdk.services.lambda.model.ListFunctionsResponse;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static ai.asserts.aws.MetricNameUtil.SCRAPE_LATENCY_METRIC;
import static ai.asserts.aws.MetricNameUtil.SCRAPE_OPERATION_LABEL;
import static ai.asserts.aws.MetricNameUtil.SCRAPE_REGION_LABEL;

@Component
@Slf4j
public class LambdaFunctionScraper {
    private final ScrapeConfigProvider scrapeConfigProvider;
    private final AWSClientProvider awsClientProvider;
    private final GaugeExporter gaugeExporter;
    private final Supplier<Map<String, Map<String, LambdaFunction>>> functionsByRegion;
    private final TagFilterResourceProvider tagFilterResourceProvider;

    public LambdaFunctionScraper(ScrapeConfigProvider scrapeConfigProvider, AWSClientProvider awsClientProvider,
                                 GaugeExporter gaugeExporter,
                                 TagFilterResourceProvider tagFilterResourceProvider) {
        this.scrapeConfigProvider = scrapeConfigProvider;
        this.awsClientProvider = awsClientProvider;
        this.gaugeExporter = gaugeExporter;
        this.tagFilterResourceProvider = tagFilterResourceProvider;
        this.functionsByRegion = Suppliers.memoizeWithExpiration(this::discoverFunctions, 15, TimeUnit.MINUTES);
    }

    public Map<String, Map<String, LambdaFunction>> getFunctions() {
        return functionsByRegion.get();
    }

    private Map<String, Map<String, LambdaFunction>> discoverFunctions() {
        Map<String, Map<String, LambdaFunction>> functionsByRegion = new TreeMap<>();
        ScrapeConfig scrapeConfig = scrapeConfigProvider.getScrapeConfig();
        Optional<NamespaceConfig> lambdaNSOpt = scrapeConfig.getNamespaces().stream()
                .filter(ns -> CWNamespace.lambda.name().equals(ns.getName()))
                .findFirst();
        lambdaNSOpt.ifPresent(lambdaNS -> scrapeConfig.getRegions().forEach(region -> {
            try {
                LambdaClient client = awsClientProvider.getLambdaClient(region);

                // Get all the functions
                long timeTaken = System.currentTimeMillis();
                ListFunctionsResponse lambdaFunctions = client.listFunctions();
                captureLatency(region, System.currentTimeMillis() - timeTaken);
                if (lambdaFunctions.hasFunctions()) {
                    Set<Resource> resources = tagFilterResourceProvider.getFilteredResources(region, lambdaNS);
                    lambdaFunctions.functions().forEach(functionConfiguration ->
                            findFunctionResource(resources, functionConfiguration).ifPresent(resource ->
                                    functionsByRegion.computeIfAbsent(region, k -> new HashMap<>())
                                            .put(functionConfiguration.functionArn(), LambdaFunction.builder()
                                                    .region(region)
                                                    .name(functionConfiguration.functionName())
                                                    .arn(functionConfiguration.functionArn())
                                                    .resource(resource)
                                                    .build())));
                }
            } catch (Exception e) {
                log.error("Failed to retrieve lambda functions", e);
            }
        }));

        return functionsByRegion;
    }

    private Optional<Resource> findFunctionResource(Set<Resource> resources,
                                                    FunctionConfiguration functionConfiguration) {
        return resources.stream()
                .filter(resource -> resource.getArn().equals(functionConfiguration.functionArn()))
                .findFirst();
    }

    private void captureLatency(String region, long timeTaken) {
        gaugeExporter.exportMetric(SCRAPE_LATENCY_METRIC, "scraper Instrumentation",
                ImmutableMap.of(
                        SCRAPE_REGION_LABEL, region,
                        SCRAPE_OPERATION_LABEL, "scrape_lambda_functions"
                ), Instant.now(), timeTaken * 1.0D);
    }
}
