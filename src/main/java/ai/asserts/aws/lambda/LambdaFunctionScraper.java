
package ai.asserts.aws.lambda;

import ai.asserts.aws.AWSClientProvider;
import ai.asserts.aws.AccountProvider;
import ai.asserts.aws.AccountProvider.AWSAccount;
import ai.asserts.aws.RateLimiter;
import ai.asserts.aws.ScrapeConfigProvider;
import ai.asserts.aws.config.NamespaceConfig;
import ai.asserts.aws.config.ScrapeConfig;
import ai.asserts.aws.model.CWNamespace;
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

import static ai.asserts.aws.MetricNameUtil.SCRAPE_ACCOUNT_ID_LABEL;
import static ai.asserts.aws.MetricNameUtil.SCRAPE_NAMESPACE_LABEL;
import static ai.asserts.aws.MetricNameUtil.SCRAPE_OPERATION_LABEL;
import static ai.asserts.aws.MetricNameUtil.SCRAPE_REGION_LABEL;
import static java.util.concurrent.TimeUnit.MINUTES;

@Component
@Slf4j
public class LambdaFunctionScraper {
    private final AccountProvider accountProvider;
    private final ScrapeConfigProvider scrapeConfigProvider;
    private final AWSClientProvider awsClientProvider;
    private final LambdaFunctionBuilder fnBuilder;
    private final ResourceTagHelper resourceTagHelper;
    private final RateLimiter rateLimiter;
    private final Supplier<Map<String, Map<String, Map<String, LambdaFunction>>>> functionsByRegion;

    public LambdaFunctionScraper(
            AccountProvider accountProvider,
            ScrapeConfigProvider scrapeConfigProvider, AWSClientProvider awsClientProvider,
            ResourceTagHelper resourceTagHelper,
            LambdaFunctionBuilder fnBuilder, RateLimiter rateLimiter) {
        this.accountProvider = accountProvider;
        this.scrapeConfigProvider = scrapeConfigProvider;
        this.awsClientProvider = awsClientProvider;
        this.resourceTagHelper = resourceTagHelper;
        this.fnBuilder = fnBuilder;
        this.rateLimiter = rateLimiter;
        this.functionsByRegion = Suppliers.memoizeWithExpiration(this::discoverFunctions,
                scrapeConfigProvider.getScrapeConfig().getListFunctionsResultCacheTTLMinutes(), MINUTES);
    }

    public Map<String, Map<String, Map<String, LambdaFunction>>> getFunctions() {
        return functionsByRegion.get();
    }

    private Map<String, Map<String, Map<String, LambdaFunction>>> discoverFunctions() {
        Map<String, Map<String, Map<String, LambdaFunction>>> functionsByRegion = new TreeMap<>();
        ScrapeConfig scrapeConfig = scrapeConfigProvider.getScrapeConfig();
        Optional<NamespaceConfig> lambdaNSOpt = scrapeConfig.getNamespaces().stream()
                .filter(ns -> CWNamespace.lambda.getNamespace().equals(ns.getName()))
                .findFirst();
        for (AWSAccount accountRegion : accountProvider.getAccounts()) {
            lambdaNSOpt.ifPresent(lambdaNS -> accountRegion.getRegions().forEach(region -> {
                try (LambdaClient lambdaClient = awsClientProvider.getLambdaClient(region, accountRegion)) {
                    // Get all the functions
                    ListFunctionsResponse response = rateLimiter.doWithRateLimit(
                            "LambdaClient/listFunctions",
                            ImmutableSortedMap.of(
                                    SCRAPE_ACCOUNT_ID_LABEL, accountRegion.getAccountId(),
                                    SCRAPE_REGION_LABEL, region,
                                    SCRAPE_NAMESPACE_LABEL, "AWS/Lambda",
                                    SCRAPE_OPERATION_LABEL, "listFunctions"
                            ),
                            lambdaClient::listFunctions);
                    if (response.hasFunctions()) {
                        Set<Resource> resources = resourceTagHelper.getFilteredResources(accountRegion, region, lambdaNS);
                        response.functions().forEach(fnConfig -> {
                            Optional<Resource> fnResourceOpt = findFnResource(resources, fnConfig);
                            functionsByRegion
                                    .computeIfAbsent(accountRegion.getAccountId(), k -> new TreeMap<>())
                                    .computeIfAbsent(region, k -> new TreeMap<>())
                                    .computeIfAbsent(fnConfig.functionArn(), k ->
                                            fnBuilder.buildFunction(region, fnConfig, fnResourceOpt));
                        });
                    }
                } catch (Exception e) {
                    log.error("Failed to retrieve lambda functions", e);
                }
            }));
        }


        return functionsByRegion;
    }

    private Optional<Resource> findFnResource(Set<Resource> resources,
                                              FunctionConfiguration functionConfiguration) {
        return resources.stream()
                .filter(resource -> resource.getArn().equals(functionConfiguration.functionArn()))
                .findFirst();
    }
}
