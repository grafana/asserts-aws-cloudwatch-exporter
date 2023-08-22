/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.exporter;

import ai.asserts.aws.AWSClientProvider;
import ai.asserts.aws.CollectionBuilderTask;
import ai.asserts.aws.MetricNameUtil;
import ai.asserts.aws.AWSApiCallRateLimiter;
import ai.asserts.aws.ScrapeConfigProvider;
import ai.asserts.aws.TaskExecutorUtil;
import ai.asserts.aws.account.AWSAccount;
import ai.asserts.aws.account.AccountProvider;
import ai.asserts.aws.config.NamespaceConfig;
import ai.asserts.aws.config.ScrapeConfig;
import ai.asserts.aws.lambda.LambdaFunction;
import ai.asserts.aws.lambda.LambdaFunctionScraper;
import ai.asserts.aws.resource.ResourceMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedMap;
import io.prometheus.client.Collector;
import io.prometheus.client.Collector.MetricFamilySamples.Sample;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.DestinationConfig;
import software.amazon.awssdk.services.lambda.model.ListFunctionEventInvokeConfigsRequest;
import software.amazon.awssdk.services.lambda.model.ListFunctionEventInvokeConfigsResponse;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;

import static ai.asserts.aws.MetricNameUtil.SCRAPE_ACCOUNT_ID_LABEL;
import static ai.asserts.aws.MetricNameUtil.SCRAPE_NAMESPACE_LABEL;
import static ai.asserts.aws.MetricNameUtil.SCRAPE_OPERATION_LABEL;
import static ai.asserts.aws.MetricNameUtil.SCRAPE_REGION_LABEL;
import static ai.asserts.aws.model.CWNamespace.lambda;
import static java.lang.String.format;

@Component
@Slf4j
public class LambdaInvokeConfigExporter extends Collector implements MetricProvider {
    private final AccountProvider accountProvider;
    private final LambdaFunctionScraper fnScraper;
    private final AWSClientProvider awsClientProvider;
    private final MetricNameUtil metricNameUtil;
    private final ScrapeConfigProvider scrapeConfigProvider;
    private final ResourceMapper resourceMapper;
    private final MetricSampleBuilder metricSampleBuilder;
    private final AWSApiCallRateLimiter rateLimiter;
    private final TaskExecutorUtil taskExecutorUtil;
    private volatile List<MetricFamilySamples> cache;

    public LambdaInvokeConfigExporter(
            AccountProvider accountProvider,
            LambdaFunctionScraper fnScraper, AWSClientProvider awsClientProvider,
            MetricNameUtil metricNameUtil,
            ScrapeConfigProvider scrapeConfigProvider, ResourceMapper resourceMapper,
            MetricSampleBuilder metricSampleBuilder,
            AWSApiCallRateLimiter rateLimiter, TaskExecutorUtil taskExecutorUtil) {
        this.accountProvider = accountProvider;
        this.fnScraper = fnScraper;
        this.awsClientProvider = awsClientProvider;
        this.metricNameUtil = metricNameUtil;
        this.scrapeConfigProvider = scrapeConfigProvider;
        this.resourceMapper = resourceMapper;
        this.metricSampleBuilder = metricSampleBuilder;
        this.rateLimiter = rateLimiter;
        this.taskExecutorUtil = taskExecutorUtil;
        this.cache = Collections.emptyList();
    }

    @Override
    public List<MetricFamilySamples> collect() {
        return cache;
    }

    @Override
    public void update() {
        log.info("Updating Lambda Invoke Configurations");
        try {
            cache = getInvokeConfigs();
        } catch (Exception e) {
            log.error("Failed to discover Lambda invoke configurations", e);
        }
    }

    List<MetricFamilySamples> getInvokeConfigs() {
        List<Sample> allSamples = new ArrayList<>();
        String metricPrefix = metricNameUtil.getMetricPrefix(lambda.getNamespace());
        String metricName = format("%s_invoke_config", metricPrefix);

        Map<String, Map<String, Map<String, LambdaFunction>>> byAccountByRegion =
                new ConcurrentHashMap<>(fnScraper.getFunctions());
        List<Future<List<Sample>>> futures = new ArrayList<>();
        for (AWSAccount accountRegion : accountProvider.getAccounts()) {
            ScrapeConfig scrapeConfig = scrapeConfigProvider.getScrapeConfig(accountRegion.getTenant());
            Optional<NamespaceConfig> opt = scrapeConfig.getLambdaConfig();
            String account = accountRegion.getAccountId();
            if (opt.isPresent() && byAccountByRegion.containsKey(account)) {
                Map<String, Map<String, LambdaFunction>> byRegion =
                        new ConcurrentHashMap<>(byAccountByRegion.get(account));
                byRegion.forEach((region, byARN) ->
                        futures.add(taskExecutorUtil.executeAccountTask(accountRegion,
                                new CollectionBuilderTask<Sample>() {
                                    @Override
                                    public List<Sample> call() {
                                        return buildSamples(byARN, region, accountRegion, account, metricName);
                                    }
                                })));
            }
        }
        taskExecutorUtil.awaitAll(futures, allSamples::addAll);
        return ImmutableList.of(new MetricFamilySamples(metricName, Type.GAUGE, "", allSamples));
    }

    private List<Sample> buildSamples(Map<String, LambdaFunction> byARN, String region, AWSAccount accountRegion,
                                      String account, String metricName) {
        List<Sample> samples = new ArrayList<>();
        byARN.forEach((arn, fnConfig) -> {
            try {
                LambdaClient client =
                        awsClientProvider.getLambdaClient(region, accountRegion);
                ListFunctionEventInvokeConfigsRequest request =
                        ListFunctionEventInvokeConfigsRequest.builder()
                                .functionName(fnConfig.getName())
                                .build();
                String listInvokeConfigs = "LambdaClient/listFunctionEventInvokeConfigs";
                ListFunctionEventInvokeConfigsResponse resp = rateLimiter.doWithRateLimit(
                        listInvokeConfigs,
                        ImmutableSortedMap.of(
                                SCRAPE_ACCOUNT_ID_LABEL, account,
                                SCRAPE_REGION_LABEL, region,
                                SCRAPE_OPERATION_LABEL, listInvokeConfigs,
                                SCRAPE_NAMESPACE_LABEL, "AWS/Lambda"
                        ),
                        () -> client.listFunctionEventInvokeConfigs(request));
                if (resp.hasFunctionEventInvokeConfigs() && resp.functionEventInvokeConfigs()
                        .size() > 0) {
                    log.info("Function {} has invoke configs", fnConfig.getName());
                    resp.functionEventInvokeConfigs().forEach(config -> {
                        Map<String, String> labels = new TreeMap<>();
                        labels.put("region", region);
                        labels.put("d_function_name", fnConfig.getName());
                        labels.put(SCRAPE_ACCOUNT_ID_LABEL, fnConfig.getAccount());
                        if (fnConfig.getResource() != null) {
                            fnConfig.getResource().addEnvLabel(labels, metricNameUtil);
                        }

                        DestinationConfig destConfig = config.destinationConfig();

                        // Success
                        if (destConfig.onSuccess() != null && destConfig.onSuccess()
                                .destination() != null) {
                            String urn = destConfig.onSuccess().destination();
                            resourceMapper.map(urn).ifPresent(targetResource -> {
                                labels.put("on", "success");
                                targetResource.addLabels(labels, "destination");
                                metricSampleBuilder.buildSingleSample(metricName, labels,
                                                1.0D)
                                        .ifPresent(samples::add);
                            });
                        }

                        // Failure
                        if (destConfig.onFailure() != null && destConfig.onFailure()
                                .destination() != null) {
                            String urn = destConfig.onFailure().destination();
                            resourceMapper.map(urn).ifPresent(targetResource -> {
                                labels.put("on", "failure");
                                targetResource.addLabels(labels, "destination");
                                metricSampleBuilder.buildSingleSample(metricName, labels,
                                                1.0D)
                                        .ifPresent(samples::add);
                            });
                        }
                    });
                }
            } catch (Exception e) {
                log.error(
                        "Failed to get function invoke config for function " + fnConfig.getArn(),
                        e);
            }
        });
        return samples;
    }
}
