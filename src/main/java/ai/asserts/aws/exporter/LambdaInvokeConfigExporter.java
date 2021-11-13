/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.exporter;

import ai.asserts.aws.AWSClientProvider;
import ai.asserts.aws.MetricNameUtil;
import ai.asserts.aws.cloudwatch.config.NamespaceConfig;
import ai.asserts.aws.cloudwatch.config.ScrapeConfig;
import ai.asserts.aws.cloudwatch.config.ScrapeConfigProvider;
import ai.asserts.aws.lambda.LambdaFunctionScraper;
import ai.asserts.aws.resource.ResourceMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedMap;
import io.prometheus.client.Collector;
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

import static ai.asserts.aws.MetricNameUtil.SCRAPE_ERROR_COUNT_METRIC;
import static ai.asserts.aws.MetricNameUtil.SCRAPE_OPERATION_LABEL;
import static ai.asserts.aws.MetricNameUtil.SCRAPE_REGION_LABEL;
import static ai.asserts.aws.cloudwatch.model.CWNamespace.lambda;
import static java.lang.String.format;

@Component
@Slf4j
public class LambdaInvokeConfigExporter extends Collector implements MetricProvider {
    private final LambdaFunctionScraper fnScraper;
    private final AWSClientProvider awsClientProvider;
    private final MetricNameUtil metricNameUtil;
    private final ScrapeConfigProvider scrapeConfigProvider;
    private final ResourceMapper resourceMapper;
    private final MetricSampleBuilder metricSampleBuilder;
    private final BasicMetricCollector metricCollector;
    private volatile List<MetricFamilySamples> cache;

    public LambdaInvokeConfigExporter(LambdaFunctionScraper fnScraper, AWSClientProvider awsClientProvider,
                                      MetricNameUtil metricNameUtil,
                                      ScrapeConfigProvider scrapeConfigProvider, ResourceMapper resourceMapper,
                                      MetricSampleBuilder metricSampleBuilder,
                                      BasicMetricCollector metricCollector) {
        this.fnScraper = fnScraper;
        this.awsClientProvider = awsClientProvider;
        this.metricNameUtil = metricNameUtil;
        this.scrapeConfigProvider = scrapeConfigProvider;
        this.resourceMapper = resourceMapper;
        this.metricSampleBuilder = metricSampleBuilder;
        this.metricCollector = metricCollector;
        this.cache = Collections.emptyList();
    }

    @Override
    public List<MetricFamilySamples> collect() {
        return cache;
    }

    @Override
    public void update() {
        cache = getInvokeConfigs();
    }

    List<MetricFamilySamples> getInvokeConfigs() {
        List<MetricFamilySamples.Sample> samples = new ArrayList<>();
        String metricPrefix = metricNameUtil.getMetricPrefix(lambda.getNamespace());
        String metricName = format("%s_invoke_config", metricPrefix);
        ScrapeConfig scrapeConfig = scrapeConfigProvider.getScrapeConfig();
        Optional<NamespaceConfig> opt = scrapeConfig.getLambdaConfig();
        opt.ifPresent(ns -> fnScraper.getFunctions().forEach((region, byARN) -> byARN.forEach((arn, fnConfig) -> {
            try (LambdaClient client = awsClientProvider.getLambdaClient(region)) {
                ListFunctionEventInvokeConfigsRequest request = ListFunctionEventInvokeConfigsRequest.builder()
                        .functionName(fnConfig.getName())
                        .build();
                ListFunctionEventInvokeConfigsResponse resp = client.listFunctionEventInvokeConfigs(request);
                if (resp.hasFunctionEventInvokeConfigs() && resp.functionEventInvokeConfigs().size() > 0) {
                    log.info("Function {} has invoke configs", fnConfig.getName());
                    resp.functionEventInvokeConfigs().forEach(config -> {
                        Map<String, String> labels = new TreeMap<>();
                        labels.put("region", region);
                        labels.put("d_function_name", fnConfig.getName());
                        if (fnConfig.getResource() != null) {
                            fnConfig.getResource().addTagLabels(labels, metricNameUtil);
                        }

                        DestinationConfig destConfig = config.destinationConfig();

                        // Success
                        if (destConfig.onSuccess() != null && destConfig.onSuccess().destination() != null) {
                            String urn = destConfig.onSuccess().destination();
                            resourceMapper.map(urn).ifPresent(targetResource -> {
                                labels.put("on", "success");
                                targetResource.addLabels(labels, "destination");
                                samples.add(
                                        metricSampleBuilder.buildSingleSample(metricName, labels, 1.0D));
                            });
                        }

                        // Failure
                        if (destConfig.onFailure() != null && destConfig.onFailure().destination() != null) {
                            String urn = destConfig.onFailure().destination();
                            resourceMapper.map(urn).ifPresent(targetResource -> {
                                labels.put("on", "failure");
                                targetResource.addLabels(labels, "destination");
                                samples.add(
                                        metricSampleBuilder.buildSingleSample(metricName, labels, 1.0D));
                            });
                        }
                    });
                }
            } catch (Exception e) {
                log.error("Failed to get function invoke config for function " + fnConfig.getArn(), e);
                metricCollector.recordCounterValue(SCRAPE_ERROR_COUNT_METRIC, ImmutableSortedMap.of(
                        SCRAPE_REGION_LABEL, region, SCRAPE_OPERATION_LABEL, "ListEventSourceMappings"
                ), 1);
            }
        })));
        return ImmutableList.of(new MetricFamilySamples(metricName, Type.GAUGE, "", samples));
    }
}
