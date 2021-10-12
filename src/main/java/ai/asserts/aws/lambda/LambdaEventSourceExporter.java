/*
 * Copyright © 2021
 * Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.lambda;

import ai.asserts.aws.AWSClientProvider;
import ai.asserts.aws.MetricNameUtil;
import ai.asserts.aws.cloudwatch.config.ScrapeConfig;
import ai.asserts.aws.cloudwatch.config.ScrapeConfigProvider;
import ai.asserts.aws.cloudwatch.prometheus.GaugeExporter;
import ai.asserts.aws.resource.Resource;
import ai.asserts.aws.resource.ResourceMapper;
import ai.asserts.aws.resource.TagFilterResourceProvider;
import com.google.common.annotations.VisibleForTesting;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.ListEventSourceMappingsResponse;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TimerTask;
import java.util.TreeMap;

import static ai.asserts.aws.cloudwatch.model.CWNamespace.lambda;
import static java.lang.String.format;

@Component
@AllArgsConstructor
@Slf4j
public class LambdaEventSourceExporter extends TimerTask {
    public static final String HELP_TEXT = "Metric with lambda event source information";
    private final ScrapeConfigProvider scrapeConfigProvider;
    private final AWSClientProvider awsClientProvider;
    private final MetricNameUtil metricNameUtil;
    private final GaugeExporter gaugeExporter;
    private final ResourceMapper resourceMapper;
    private final TagFilterResourceProvider tagFilterResourceProvider;

    public void run() {
        ScrapeConfig scrapeConfig = scrapeConfigProvider.getScrapeConfig();
        scrapeConfig.getLambdaConfig().ifPresent(namespaceConfig -> scrapeConfig.getRegions().forEach(region -> {
            try {
                LambdaClient client = awsClientProvider.getLambdaClient(region);
                Set<Resource> functionResources = tagFilterResourceProvider.getFilteredResources(region, namespaceConfig);

                // Get all event source mappings
                log.info("Discovering Lambda event source mappings for region={}", region);
                ListEventSourceMappingsResponse mappings = client.listEventSourceMappings();
                Instant now = now();
                if (mappings.hasEventSourceMappings()) {
                    mappings.eventSourceMappings().forEach(mapping -> {
                        Optional<Resource> mappedFnOpt = resourceMapper.map(mapping.functionArn());
                        Optional<Resource> fnOpt = mappedFnOpt.flatMap(value -> functionResources.stream()
                                .filter(resource -> resource.equals(value))
                                .findFirst());
                        fnOpt.ifPresent(functionResource -> resourceMapper.map(mapping.eventSourceArn())
                                .ifPresent(eventSourceResource -> {
                                    String metricPrefix = metricNameUtil.getMetricPrefix(lambda.getNamespace());
                                    String metricName = format("%s_event_source", metricPrefix);
                                    Map<String, String> labels = new TreeMap<>();
                                    labels.put("region", region);
                                    labels.put("lambda_function", functionResource.getName());
                                    labels.putAll(metricNameUtil.getResourceTagLabels(functionResource));
                                    labels.put("event_source_type", eventSourceResource.getType().name());
                                    labels.put("event_source_name", eventSourceResource.getName());
                                    gaugeExporter.exportMetric(metricName, HELP_TEXT, labels, now, 1.0D);
                                }));
                    });
                }
            } catch (Exception e) {
                log.info("Failed to discover event source mappings", e);
            }
        }));
    }

    @VisibleForTesting
    Instant now() {
        return Instant.now();
    }
}
