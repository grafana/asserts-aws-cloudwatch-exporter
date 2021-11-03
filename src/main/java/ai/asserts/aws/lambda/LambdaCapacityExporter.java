/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.lambda;

import ai.asserts.aws.AWSClientProvider;
import ai.asserts.aws.MetricNameUtil;
import ai.asserts.aws.cloudwatch.config.NamespaceConfig;
import ai.asserts.aws.cloudwatch.config.ScrapeConfig;
import ai.asserts.aws.cloudwatch.config.ScrapeConfigProvider;
import ai.asserts.aws.cloudwatch.metrics.MetricSampleBuilder;
import ai.asserts.aws.cloudwatch.prometheus.GaugeExporter;
import ai.asserts.aws.cloudwatch.prometheus.MetricProvider;
import ai.asserts.aws.resource.Resource;
import ai.asserts.aws.resource.TagFilterResourceProvider;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableMap;
import io.prometheus.client.Collector;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.GetAccountSettingsResponse;
import software.amazon.awssdk.services.lambda.model.ListProvisionedConcurrencyConfigsRequest;
import software.amazon.awssdk.services.lambda.model.ListProvisionedConcurrencyConfigsResponse;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

import static ai.asserts.aws.MetricNameUtil.SCRAPE_LATENCY_METRIC;
import static ai.asserts.aws.MetricNameUtil.SCRAPE_OPERATION_LABEL;
import static ai.asserts.aws.MetricNameUtil.SCRAPE_REGION_LABEL;

@Component
@Slf4j
@AllArgsConstructor
public class LambdaCapacityExporter extends Collector implements MetricProvider {
    private final ScrapeConfigProvider scrapeConfigProvider;
    private final AWSClientProvider awsClientProvider;
    private final MetricNameUtil metricNameUtil;
    private final GaugeExporter gaugeExporter;
    private final MetricSampleBuilder sampleBuilder;
    private final LambdaFunctionScraper functionScraper;
    private final TagFilterResourceProvider tagFilterResourceProvider;

    public List<MetricFamilySamples> collect() {
        Instant now = now();
        ScrapeConfig scrapeConfig = scrapeConfigProvider.getScrapeConfig();
        Optional<NamespaceConfig> optional = scrapeConfig.getLambdaConfig();
        String availableMetric = metricNameUtil.getLambdaMetric("available_concurrency");
        String requestedMetric = metricNameUtil.getLambdaMetric("requested_concurrency");
        String allocatedMetric = metricNameUtil.getLambdaMetric("allocated_concurrency");
        String timeoutMetric = metricNameUtil.getLambdaMetric("timeout_seconds");
        String accountLimitMetric = metricNameUtil.getLambdaMetric("account_limit");

        Map<String, List<MetricFamilySamples.Sample>> samples = new TreeMap<>();

        optional.ifPresent(lambdaConfig -> functionScraper.getFunctions().forEach((region, functions) -> {
            log.info("Getting Lambda account and provisioned concurrency for region {}", region);
            try (LambdaClient lambdaClient = awsClientProvider.getLambdaClient(region)) {
                GetAccountSettingsResponse accountSettings = lambdaClient.getAccountSettings();

                MetricFamilySamples.Sample sample = sampleBuilder.buildSingleSample(accountLimitMetric, ImmutableMap.of(
                        "region", region,
                        "type", "concurrent_executions"
                ), now, accountSettings.accountLimit().concurrentExecutions() * 1.0D);
                samples.computeIfAbsent(accountLimitMetric, k -> new ArrayList<>()).add(sample);

                sample = sampleBuilder.buildSingleSample(accountLimitMetric, ImmutableMap.of(
                        "region", region,
                        "type", "unreserved_concurrent_executions"
                ), now, accountSettings.accountLimit().unreservedConcurrentExecutions() * 1.0D);
                samples.computeIfAbsent(accountLimitMetric, k -> new ArrayList<>()).add(sample);

                Set<Resource> fnResources = tagFilterResourceProvider.getFilteredResources(region, lambdaConfig);
                functions.forEach((functionArn, lambdaFunction) -> {
                    Optional<Resource> fnResourceOpt = fnResources.stream()
                            .filter(resource -> functionArn.equals(resource.getArn()))
                            .findFirst();

                    Map<String, String> labels = new TreeMap<>();
                    fnResourceOpt.ifPresent(fnResource -> fnResource.addTagLabels(labels, metricNameUtil));

                    labels.put("region", region);
                    labels.put("d_function_name", lambdaFunction.getName());
                    labels.put("job", lambdaFunction.getName());

                    // Export timeout
                    double timeout = lambdaFunction.getTimeoutSeconds() * 1.0D;
                    samples.computeIfAbsent(timeoutMetric, k -> new ArrayList<>())
                            .add(sampleBuilder.buildSingleSample(timeoutMetric, labels, now, timeout));

                    ListProvisionedConcurrencyConfigsRequest request = ListProvisionedConcurrencyConfigsRequest
                            .builder()
                            .functionName(lambdaFunction.getName())
                            .build();

                    long timeTaken = System.currentTimeMillis();
                    ListProvisionedConcurrencyConfigsResponse response = lambdaClient.listProvisionedConcurrencyConfigs(
                            request);
                    timeTaken = System.currentTimeMillis() - timeTaken;
                    captureLatency(region, timeTaken);

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
                                            availableMetric, labels, now, available.doubleValue()));

                            Integer requested = config.requestedProvisionedConcurrentExecutions();
                            samples.computeIfAbsent(requestedMetric, k -> new ArrayList<>())
                                    .add(sampleBuilder.buildSingleSample(
                                            requestedMetric, labels, now, requested.doubleValue()));

                            Integer allocated = config.allocatedProvisionedConcurrentExecutions();
                            samples.computeIfAbsent(allocatedMetric, k -> new ArrayList<>())
                                    .add(sampleBuilder.buildSingleSample(
                                            allocatedMetric, labels, now, allocated.doubleValue()));
                        });
                    }
                });
            } catch (Exception e) {
                log.error("Failed to get lambda provisioned capacity for region " + region, e);
            }
        }));
        List<MetricFamilySamples> collect = samples.values().stream()
                .map(sampleBuilder::buildFamily)
                .collect(Collectors.toList());
        return collect;
    }

    private void captureLatency(String region, long timeTaken) {
        gaugeExporter.exportMetric(SCRAPE_LATENCY_METRIC, "scraper Instrumentation",
                ImmutableMap.of(
                        SCRAPE_REGION_LABEL, region,
                        SCRAPE_OPERATION_LABEL, "list_provisioned_concurrency_configs"
                ), Instant.now(), timeTaken * 1.0D);
    }

    @VisibleForTesting
    Instant now() {
        return Instant.now();
    }
}
