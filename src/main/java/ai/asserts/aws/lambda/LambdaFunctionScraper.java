
package ai.asserts.aws.lambda;

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
import ai.asserts.aws.exporter.ECSServiceDiscoveryExporter;
import ai.asserts.aws.exporter.MetricProvider;
import ai.asserts.aws.exporter.MetricSampleBuilder;
import ai.asserts.aws.resource.Resource;
import ai.asserts.aws.resource.ResourceTagHelper;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableSortedMap;
import io.prometheus.client.Collector;
import io.prometheus.client.Collector.MetricFamilySamples.Sample;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.FunctionConfiguration;
import software.amazon.awssdk.services.lambda.model.ListFunctionsResponse;
import software.amazon.awssdk.services.lambda.model.VpcConfigResponse;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Future;
import java.util.function.Supplier;

import static ai.asserts.aws.MetricNameUtil.SCRAPE_ACCOUNT_ID_LABEL;
import static ai.asserts.aws.MetricNameUtil.SCRAPE_NAMESPACE_LABEL;
import static ai.asserts.aws.MetricNameUtil.SCRAPE_OPERATION_LABEL;
import static ai.asserts.aws.MetricNameUtil.SCRAPE_REGION_LABEL;
import static ai.asserts.aws.MetricNameUtil.TENANT;
import static ai.asserts.aws.model.CWNamespace.lambda;
import static java.util.concurrent.TimeUnit.MINUTES;

@Component
@Slf4j
public class LambdaFunctionScraper extends Collector implements MetricProvider {
    public static final String ONLY_LAMBDAS_IN_THIS_ENV = "ONLY_LAMBDAS_IN_THIS_ENV";
    private final AccountProvider accountProvider;
    private final ScrapeConfigProvider scrapeConfigProvider;
    private final AWSClientProvider awsClientProvider;
    private final LambdaFunctionBuilder fnBuilder;
    private final ResourceTagHelper resourceTagHelper;
    private final AWSApiCallRateLimiter rateLimiter;
    private final MetricSampleBuilder metricSampleBuilder;
    private final MetricNameUtil metricNameUtil;
    private final ECSServiceDiscoveryExporter ecsSDExporter;
    private final TaskExecutorUtil taskExecutorUtil;
    private final Supplier<Map<String, Map<String, Map<String, LambdaFunction>>>> functionsByRegion;
    private final boolean filterLambdaByEnvironment;
    private List<MetricFamilySamples> cache;

    public LambdaFunctionScraper(
            AccountProvider accountProvider,
            ScrapeConfigProvider scrapeConfigProvider, AWSClientProvider awsClientProvider,
            ResourceTagHelper resourceTagHelper,
            LambdaFunctionBuilder fnBuilder, AWSApiCallRateLimiter rateLimiter,
            MetricSampleBuilder metricSampleBuilder, MetricNameUtil metricNameUtil,
            ECSServiceDiscoveryExporter ecsSDExporter, TaskExecutorUtil taskExecutorUtil) {
        this.accountProvider = accountProvider;
        this.scrapeConfigProvider = scrapeConfigProvider;
        this.awsClientProvider = awsClientProvider;
        this.resourceTagHelper = resourceTagHelper;
        this.fnBuilder = fnBuilder;
        this.rateLimiter = rateLimiter;
        this.metricSampleBuilder = metricSampleBuilder;
        this.metricNameUtil = metricNameUtil;
        this.taskExecutorUtil = taskExecutorUtil;
        this.functionsByRegion = Suppliers.memoizeWithExpiration(this::discoverFunctions,
                5, MINUTES);
        this.ecsSDExporter = ecsSDExporter;
        this.cache = new ArrayList<>();
        this.filterLambdaByEnvironment = "true".equalsIgnoreCase(lambdaEnvFilterFlag());
    }

    @VisibleForTesting
    String lambdaEnvFilterFlag() {
        return System.getenv(ONLY_LAMBDAS_IN_THIS_ENV);
    }

    public Map<String, Map<String, Map<String, LambdaFunction>>> getFunctions() {
        return functionsByRegion.get();
    }

    @Override
    public void update() {
        log.info("Discover Lambda functions");
        try {
            Map<String, Map<String, Map<String, LambdaFunction>>> byAccount = getFunctions();
            List<Sample> samples = new ArrayList<>();
            byAccount.forEach(
                    (accountId, byRegion) -> byRegion.forEach((region, byName) -> byName.forEach((name, details)
                            -> {
                        Map<String, String> labels = new TreeMap<>();
                        labels.put(TENANT, details.getTenant());
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
                metricSampleBuilder.buildFamily(samples).ifPresent(family -> cache = Collections.singletonList(family));
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
        Map<String, Map<String, Map<String, LambdaFunction>>> byAccountByRegion = new TreeMap<>();

        List<Future<Map<String, Map<String, Map<String, LambdaFunction>>>>> futures = new ArrayList<>();
        LambdaFunctionScraper scraper = this;
        for (AWSAccount accountRegion : accountProvider.getAccounts()) {
            ScrapeConfig scrapeConfig = scrapeConfigProvider.getScrapeConfig(accountRegion.getTenant());
            Optional<NamespaceConfig> lambdaNSOpt = scrapeConfig.getNamespaces().stream()
                    .filter(ns -> lambda.getNamespace().equals(ns.getName()))
                    .findFirst();
            lambdaNSOpt.ifPresent(lambdaNS -> accountRegion.getRegions().forEach(region ->
                    futures.add(taskExecutorUtil.executeAccountTask(accountRegion,
                            new SimpleTenantTask<Map<String, Map<String, Map<String, LambdaFunction>>>>() {
                                @Override
                                public Map<String, Map<String, Map<String, LambdaFunction>>> call() {
                                    return buildLookupMap(region, accountRegion, lambdaNS, scraper);
                                }
                            }))));
        }
        taskExecutorUtil.awaitAll(futures, (srcByAccount) -> srcByAccount.forEach((srcAccount, srcByRegion) -> {
            Map<String, Map<String, LambdaFunction>> byRegion =
                    byAccountByRegion.computeIfAbsent(srcAccount, k -> new TreeMap<>());
            srcByRegion.forEach((region, byName) ->
                    byRegion.computeIfAbsent(region, k -> new TreeMap<>()).putAll(byName));
        }));
        return byAccountByRegion;
    }

    private Map<String, Map<String, Map<String, LambdaFunction>>> buildLookupMap(String region,
                                                                                 AWSAccount accountRegion,
                                                                                 NamespaceConfig lambdaNS,
                                                                                 LambdaFunctionScraper scraper) {
        Map<String, Map<String, Map<String, LambdaFunction>>> functionsByRegion = new TreeMap<>();
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
                response.functions().stream()
                        .filter(scraper::isLambdaInSameEnvironment)
                        .forEach(fnConfig -> {
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
        return functionsByRegion;
    }

    @VisibleForTesting
    boolean isLambdaInSameEnvironment(FunctionConfiguration functionConfiguration) {
        VpcConfigResponse vpcConfigResponse = functionConfiguration.vpcConfig();
        return !filterLambdaByEnvironment || vpcConfigResponse == null ||
                (ecsSDExporter.runningInVPC(vpcConfigResponse.vpcId()) &&
                        vpcConfigResponse.subnetIds().stream().anyMatch(ecsSDExporter::runningInSubnet));
    }

    private Optional<Resource> findFnResource(Set<Resource> resources,
                                              FunctionConfiguration functionConfiguration) {
        return resources.stream()
                .filter(resource -> resource.getArn().equals(functionConfiguration.functionArn()))
                .findFirst();
    }
}
