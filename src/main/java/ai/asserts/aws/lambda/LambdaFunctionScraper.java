
package ai.asserts.aws.lambda;

import ai.asserts.aws.AWSClientProvider;
import ai.asserts.aws.AccountProvider;
import ai.asserts.aws.AccountProvider.AWSAccount;
import ai.asserts.aws.MetricNameUtil;
import ai.asserts.aws.RateLimiter;
import ai.asserts.aws.ScrapeConfigProvider;
import ai.asserts.aws.config.NamespaceConfig;
import ai.asserts.aws.config.ScrapeConfig;
import ai.asserts.aws.exporter.MetricProvider;
import ai.asserts.aws.exporter.MetricSampleBuilder;
import ai.asserts.aws.resource.Resource;
import ai.asserts.aws.resource.ResourceTagHelper;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableSortedMap;
import io.prometheus.client.Collector;
import io.prometheus.client.Collector.MetricFamilySamples.Sample;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.FunctionConfiguration;
import software.amazon.awssdk.services.lambda.model.ListFunctionsResponse;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;

import static ai.asserts.aws.MetricNameUtil.SCRAPE_ACCOUNT_ID_LABEL;
import static ai.asserts.aws.MetricNameUtil.SCRAPE_NAMESPACE_LABEL;
import static ai.asserts.aws.MetricNameUtil.SCRAPE_OPERATION_LABEL;
import static ai.asserts.aws.MetricNameUtil.SCRAPE_REGION_LABEL;
import static ai.asserts.aws.model.CWNamespace.lambda;
import static java.util.concurrent.TimeUnit.MINUTES;

@Component
@Slf4j
public class LambdaFunctionScraper extends Collector implements MetricProvider {
    private final AccountProvider accountProvider;
    private final ScrapeConfigProvider scrapeConfigProvider;
    private final AWSClientProvider awsClientProvider;
    private final LambdaFunctionBuilder fnBuilder;
    private final ResourceTagHelper resourceTagHelper;
    private final RateLimiter rateLimiter;
    private final MetricSampleBuilder metricSampleBuilder;
    private final MetricNameUtil metricNameUtil;
    private final Supplier<Map<String, Map<String, Map<String, LambdaFunction>>>> functionsByRegion;
    private List<MetricFamilySamples> cache;

    public LambdaFunctionScraper(
            AccountProvider accountProvider,
            ScrapeConfigProvider scrapeConfigProvider, AWSClientProvider awsClientProvider,
            ResourceTagHelper resourceTagHelper,
            LambdaFunctionBuilder fnBuilder, RateLimiter rateLimiter,
            MetricSampleBuilder metricSampleBuilder, MetricNameUtil metricNameUtil) {
        this.accountProvider = accountProvider;
        this.scrapeConfigProvider = scrapeConfigProvider;
        this.awsClientProvider = awsClientProvider;
        this.resourceTagHelper = resourceTagHelper;
        this.fnBuilder = fnBuilder;
        this.rateLimiter = rateLimiter;
        this.metricSampleBuilder = metricSampleBuilder;
        this.metricNameUtil = metricNameUtil;
        this.functionsByRegion = Suppliers.memoizeWithExpiration(this::discoverFunctions,
                scrapeConfigProvider.getScrapeConfig().getListFunctionsResultCacheTTLMinutes(), MINUTES);
        this.cache = new ArrayList<>();
    }

    public Map<String, Map<String, Map<String, LambdaFunction>>> getFunctions() {
        return functionsByRegion.get();
    }

    @Override
    public void update() {
        try {
            Map<String, Map<String, Map<String, LambdaFunction>>> byAccount = getFunctions();
            List<Sample> samples = new ArrayList<>();
            byAccount.forEach(
                    (accountId, byRegion) -> byRegion.forEach((region, byName) -> byName.forEach((name, details)
                            -> {
                        Map<String, String> labels = new TreeMap<>();
                        labels.put(SCRAPE_ACCOUNT_ID_LABEL, accountId);
                        labels.put(SCRAPE_REGION_LABEL, region);
                        labels.put("aws_resource_type", "AWS::Lambda::Function");
                        labels.put("namespace", lambda.getNamespace());
                        labels.put("job", details.getName());
                        labels.put("name", details.getName());
                        labels.put("id", details.getName());
                        if (details.getResource() != null) {
                            details.getResource().addTagLabels(labels, metricNameUtil);
                            details.getResource().addEnvLabel(labels, metricNameUtil);
                        }
                        metricSampleBuilder.buildSingleSample("aws_resource", labels, 1.0D)
                                .ifPresent(samples::add);
                    })));
            if (samples.size() > 0) {
                cache = Collections.singletonList(metricSampleBuilder.buildFamily(samples));
            } else {
                cache = new ArrayList<>();
            }
        } catch (Exception e) {
            log.error("Failed to export Lambda function resource metrics", e);
        }
    }

    @Override
    public List<MetricFamilySamples> collect() {
        return cache;
    }

    private Map<String, Map<String, Map<String, LambdaFunction>>> discoverFunctions() {
        Map<String, Map<String, Map<String, LambdaFunction>>> functionsByRegion = new TreeMap<>();
        ScrapeConfig scrapeConfig = scrapeConfigProvider.getScrapeConfig();
        Optional<NamespaceConfig> lambdaNSOpt = scrapeConfig.getNamespaces().stream()
                .filter(ns -> lambda.getNamespace().equals(ns.getName()))
                .findFirst();
        for (AWSAccount accountRegion : accountProvider.getAccounts()) {
            lambdaNSOpt.ifPresent(lambdaNS -> accountRegion.getRegions().forEach(region -> {
                try {
                    LambdaClient lambdaClient = awsClientProvider.getLambdaClient(region, accountRegion);
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
                        Set<Resource> resources =
                                resourceTagHelper.getFilteredResources(accountRegion, region, lambdaNS);
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
