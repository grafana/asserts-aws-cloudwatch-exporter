/*
 * Copyright © 2021
 * Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.lambda;

import ai.asserts.aws.AWSClientProvider;
import ai.asserts.aws.cloudwatch.config.ScrapeConfig;
import ai.asserts.aws.cloudwatch.config.ScrapeConfigProvider;
import ai.asserts.aws.cloudwatch.prometheus.GaugeExporter;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.ListFunctionsResponse;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static ai.asserts.aws.MetricNameUtil.SELF_LATENCY_METRIC;
import static ai.asserts.aws.MetricNameUtil.SELF_OPERATION_LABEL;
import static ai.asserts.aws.MetricNameUtil.SELF_REGION_LABEL;

@Component
@Slf4j
public class LambdaFunctionScraper {
    private final ScrapeConfigProvider scrapeConfigProvider;
    private final AWSClientProvider awsClientProvider;
    private final GaugeExporter gaugeExporter;
    private final Supplier<Map<String, Map<String, LambdaFunction>>> functionsByRegion;

    public LambdaFunctionScraper(ScrapeConfigProvider scrapeConfigProvider, AWSClientProvider awsClientProvider, GaugeExporter gaugeExporter) {
        this.scrapeConfigProvider = scrapeConfigProvider;
        this.awsClientProvider = awsClientProvider;
        this.gaugeExporter = gaugeExporter;
        this.functionsByRegion = Suppliers.memoizeWithExpiration(this::discoverFunctions, 15, TimeUnit.MINUTES);
    }

    public Map<String, Map<String, LambdaFunction>> getFunctions() {
        return functionsByRegion.get();
    }

    private Map<String, Map<String, LambdaFunction>> discoverFunctions() {
        Map<String, Map<String, LambdaFunction>> functionsByRegion = new TreeMap<>();
        ScrapeConfig scrapeConfig = scrapeConfigProvider.getScrapeConfig();
        scrapeConfig.getRegions().forEach(region -> {
            try {
                LambdaClient client = awsClientProvider.getLambdaClient(region);

                // Get all the functions
                long timeTaken = System.currentTimeMillis();
                ListFunctionsResponse lambdaFunctions = client.listFunctions();
                captureLatency(region, System.currentTimeMillis() - timeTaken);
                if (lambdaFunctions.hasFunctions()) {
                    lambdaFunctions.functions().forEach(functionConfiguration ->
                            functionsByRegion.computeIfAbsent(region, k -> new HashMap<>())
                                    .put(functionConfiguration.functionArn(), LambdaFunction.builder()
                                            .name(functionConfiguration.functionName())
                                            .arn(functionConfiguration.functionArn())
                                            .build()));
                }
            } catch (Exception e) {
                log.error("Failed to retrieve lambda functions", e);
            }
        });
        return functionsByRegion;
    }

    private void captureLatency(String region, long timeTaken) {
        gaugeExporter.exportMetric(SELF_LATENCY_METRIC, "scraper Instrumentation",
                ImmutableMap.of(
                        SELF_REGION_LABEL, region,
                        SELF_OPERATION_LABEL, "scrape_lambda_functions"
                ), Instant.now(), timeTaken * 1.0D);
    }
}
