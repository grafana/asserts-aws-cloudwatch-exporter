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
import ai.asserts.aws.cloudwatch.prometheus.GaugeExporter;
import ai.asserts.aws.resource.Resource;
import ai.asserts.aws.resource.TagFilterResourceProvider;
import com.google.common.annotations.VisibleForTesting;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.ListProvisionedConcurrencyConfigsRequest;
import software.amazon.awssdk.services.lambda.model.ListProvisionedConcurrencyConfigsResponse;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TimerTask;
import java.util.TreeMap;

@Component
@Slf4j
@Setter
public class LambdaCapacityExporter extends TimerTask {
    @Autowired
    private ScrapeConfigProvider scrapeConfigProvider;
    @Autowired
    private AWSClientProvider awsClientProvider;
    @Autowired
    private MetricNameUtil metricNameUtil;
    @Autowired
    private GaugeExporter gaugeExporter;
    @Autowired
    private LambdaFunctionScraper functionScraper;
    @Autowired
    private TagFilterResourceProvider tagFilterResourceProvider;

    @Override
    public void run() {
        Instant now = now();
        ScrapeConfig scrapeConfig = scrapeConfigProvider.getScrapeConfig();
        Optional<NamespaceConfig> optional = scrapeConfig.getLambdaConfig();
        String availableMetric = metricNameUtil.getLambdaMetric("available_concurrency");
        String requestedMetric = metricNameUtil.getLambdaMetric("requested_concurrency");
        String allocatedMetric = metricNameUtil.getLambdaMetric("allocated_concurrency");
        optional.ifPresent(lambdaConfig -> functionScraper.getFunctions().forEach((region, functions) -> {
            log.info("Getting Lambda provisioned concurrency for region {}", region);
            try {
                LambdaClient lambdaClient = awsClientProvider.getLambdaClient(region);
                Set<Resource> fnResources = tagFilterResourceProvider.getFilteredResources(region, lambdaConfig);
                functions.forEach((functionArn, lambdaFunction) -> {
                    Optional<Resource> fnResourceOpt = fnResources.stream()
                            .filter(resource -> functionArn.equals(resource.getArn()))
                            .findFirst();
                    ListProvisionedConcurrencyConfigsRequest request = ListProvisionedConcurrencyConfigsRequest
                            .builder()
                            .functionName(lambdaFunction.getName())
                            .build();
                    ListProvisionedConcurrencyConfigsResponse response = lambdaClient.listProvisionedConcurrencyConfigs(
                            request);
                    if (response.hasProvisionedConcurrencyConfigs()) {
                        response.provisionedConcurrencyConfigs().forEach(config -> {
                            Map<String, String> labels = new TreeMap<>();
                            fnResourceOpt.ifPresent(fnResource -> fnResource.addTagLabels(labels, metricNameUtil));

                            labels.put("region", region);
                            labels.put("function_name", lambdaFunction.getName());

                            // Capacity is always provisioned at alias or version level
                            String[] parts = config.functionArn().split(":");
                            String level = Character.isDigit(parts[parts.length - 1].charAt(0)) ? "version" : "alias";
                            labels.put(level, parts[parts.length - 1]);

                            Integer available = config.availableProvisionedConcurrentExecutions();
                            gaugeExporter.exportMetric(availableMetric, "", labels, now, available.doubleValue());

                            Integer requested = config.requestedProvisionedConcurrentExecutions();
                            gaugeExporter.exportMetric(requestedMetric, "", labels, now, requested.doubleValue());

                            Integer allocated = config.allocatedProvisionedConcurrentExecutions();
                            gaugeExporter.exportMetric(allocatedMetric, "", labels, now, allocated.doubleValue());
                        });
                    }
                });
            } catch (Exception e) {
                log.error("Failed to get lambda provisioned capacity for region " + region, e);
            }
        }));
    }

    @VisibleForTesting
    Instant now() {
        return Instant.now();
    }
}
