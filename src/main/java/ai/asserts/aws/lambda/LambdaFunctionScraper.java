package ai.asserts.aws.lambda;

import ai.asserts.aws.AWSClientProvider;
import ai.asserts.aws.RateLimiter;
import ai.asserts.aws.cloudwatch.config.NamespaceConfig;
import ai.asserts.aws.cloudwatch.config.ScrapeConfig;
import ai.asserts.aws.cloudwatch.config.ScrapeConfigProvider;
import ai.asserts.aws.cloudwatch.model.CWNamespace;
import ai.asserts.aws.resource.Resource;
import ai.asserts.aws.resource.ResourceTagHelper;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableSortedMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.FunctionConfiguration;
import software.amazon.awssdk.services.lambda.model.ListFunctionsResponse;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;

import static ai.asserts.aws.MetricNameUtil.SCRAPE_NAMESPACE_LABEL;
import static ai.asserts.aws.MetricNameUtil.SCRAPE_OPERATION_LABEL;
import static ai.asserts.aws.MetricNameUtil.SCRAPE_REGION_LABEL;
import static java.util.concurrent.TimeUnit.MINUTES;

@Component
@Slf4j
public class LambdaFunctionScraper {
    private final ScrapeConfigProvider scrapeConfigProvider;
    private final AWSClientProvider awsClientProvider;
    private final LambdaFunctionBuilder fnBuilder;
    private final ResourceTagHelper resourceTagHelper;
    private final RateLimiter rateLimiter;
    private final Supplier<Map<String, Map<String, LambdaFunction>>> functionsByRegion;

    public LambdaFunctionScraper(ScrapeConfigProvider scrapeConfigProvider, AWSClientProvider awsClientProvider,
                                 ResourceTagHelper resourceTagHelper,
                                 LambdaFunctionBuilder fnBuilder, RateLimiter rateLimiter) {
        this.scrapeConfigProvider = scrapeConfigProvider;
        this.awsClientProvider = awsClientProvider;
        this.resourceTagHelper = resourceTagHelper;
        this.fnBuilder = fnBuilder;
        this.rateLimiter = rateLimiter;
        this.functionsByRegion = Suppliers.memoizeWithExpiration(this::discoverFunctions,
                scrapeConfigProvider.getScrapeConfig().getListFunctionsResultCacheTTLMinutes(), MINUTES);
    }

    public Map<String, Map<String, LambdaFunction>> getFunctions() {
        return functionsByRegion.get();
    }

    private Map<String, Map<String, LambdaFunction>> discoverFunctions() {
        Map<String, Map<String, LambdaFunction>> functionsByRegion = new TreeMap<>();
        ScrapeConfig scrapeConfig = scrapeConfigProvider.getScrapeConfig();
        Optional<NamespaceConfig> lambdaNSOpt = scrapeConfig.getNamespaces().stream()
                .filter(ns -> CWNamespace.lambda.getNamespace().equals(ns.getName()))
                .findFirst();
        lambdaNSOpt.ifPresent(lambdaNS -> scrapeConfig.getRegions().forEach(region -> {
            try (LambdaClient lambdaClient = awsClientProvider.getLambdaClient(region, scrapeConfig.getAssumeRole())) {
                // Get all the functions
                ListFunctionsResponse response = rateLimiter.doWithRateLimit(
                        "LambdaClient/listFunctions",
                        ImmutableSortedMap.of(
                                SCRAPE_REGION_LABEL, region,
                                SCRAPE_NAMESPACE_LABEL, "AWS/Lambda",
                                SCRAPE_OPERATION_LABEL, "listFunctions"
                        ),
                        lambdaClient::listFunctions);
                if (response.hasFunctions()) {
                    Set<Resource> resources = resourceTagHelper.getFilteredResources(region, lambdaNS);
                    response.functions().forEach(fnConfig -> {
                        Optional<Resource> fnResourceOpt = findFnResource(resources, fnConfig);
                        functionsByRegion
                                .computeIfAbsent(region, k -> new TreeMap<>())
                                .computeIfAbsent(fnConfig.functionArn(), k ->
                                        fnBuilder.buildFunction(region, fnConfig, fnResourceOpt));
                    });
                }
            } catch (Exception e) {
                log.error("Failed to retrieve lambda functions", e);
            }
        }));

        return functionsByRegion;
    }

    private Optional<Resource> findFnResource(Set<Resource> resources,
                                              FunctionConfiguration functionConfiguration) {
        return resources.stream()
                .filter(resource -> resource.getArn().equals(functionConfiguration.functionArn()))
                .findFirst();
    }
}
