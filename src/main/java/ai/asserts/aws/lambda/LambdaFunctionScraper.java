/*
 * Copyright © 2021
 * Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.lambda;

import ai.asserts.aws.AWSClientProvider;
import ai.asserts.aws.cloudwatch.config.ScrapeConfig;
import ai.asserts.aws.cloudwatch.config.ScrapeConfigProvider;
import com.google.common.base.Suppliers;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.ListFunctionsResponse;

import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

@Component
@Slf4j
public class LambdaFunctionScraper {
    private final ScrapeConfigProvider scrapeConfigProvider;
    private final AWSClientProvider awsClientProvider;
    private final Supplier<Map<String, Map<String, LambdaFunction>>> functionsByRegion;

    public LambdaFunctionScraper(ScrapeConfigProvider scrapeConfigProvider, AWSClientProvider awsClientProvider) {
        this.scrapeConfigProvider = scrapeConfigProvider;
        this.awsClientProvider = awsClientProvider;
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
                ListFunctionsResponse lambdaFunctions = client.listFunctions();
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
}
