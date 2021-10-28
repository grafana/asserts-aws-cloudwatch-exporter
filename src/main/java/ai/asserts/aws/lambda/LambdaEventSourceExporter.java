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
import com.google.common.base.Suppliers;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.EventSourceMappingConfiguration;
import software.amazon.awssdk.services.lambda.model.ListEventSourceMappingsRequest;
import software.amazon.awssdk.services.lambda.model.ListEventSourceMappingsResponse;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TimerTask;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static ai.asserts.aws.cloudwatch.model.CWNamespace.lambda;
import static java.lang.String.format;

@Component
@Slf4j
public class LambdaEventSourceExporter extends TimerTask {
    public static final String HELP_TEXT = "Metric with lambda event source information";
    private final ScrapeConfigProvider scrapeConfigProvider;
    private final AWSClientProvider awsClientProvider;
    private final MetricNameUtil metricNameUtil;
    private final GaugeExporter gaugeExporter;
    private final ResourceMapper resourceMapper;
    private final TagFilterResourceProvider tagFilterResourceProvider;
    private final Supplier<Map<String, List<EventSourceMappingConfiguration>>> eventSourceMappings;

    public LambdaEventSourceExporter(ScrapeConfigProvider scrapeConfigProvider, AWSClientProvider awsClientProvider,
                                     MetricNameUtil metricNameUtil, GaugeExporter gaugeExporter,
                                     ResourceMapper resourceMapper,
                                     TagFilterResourceProvider tagFilterResourceProvider) {
        this.scrapeConfigProvider = scrapeConfigProvider;
        this.awsClientProvider = awsClientProvider;
        this.metricNameUtil = metricNameUtil;
        this.gaugeExporter = gaugeExporter;
        this.resourceMapper = resourceMapper;
        this.tagFilterResourceProvider = tagFilterResourceProvider;
        this.eventSourceMappings = Suppliers.memoizeWithExpiration(this::getMappings, 15, TimeUnit.MINUTES);
    }

    public void run() {
        scrapeConfigProvider.getScrapeConfig().getLambdaConfig().ifPresent(nc ->
                eventSourceMappings.get().forEach((region, mappings) -> {
                    Set<Resource> fnResources = tagFilterResourceProvider.getFilteredResources(region, nc);
                    mappings.forEach(mappingConfiguration -> {
                        Optional<Resource> fnResource = Optional.ofNullable(fnResources.stream()
                                .filter(r -> r.getArn().equals(mappingConfiguration.functionArn()))
                                .findFirst()
                                .orElse(resourceMapper.map(mappingConfiguration.functionArn()).orElse(null)));
                        Optional<Resource> eventResourceOpt = resourceMapper.map(mappingConfiguration.eventSourceArn());
                        eventResourceOpt.ifPresent(eventResource ->
                                fnResource.ifPresent(fn -> emitMetric(region, now(), fn, eventResource))
                        );
                    });
                }));
    }

    private Map<String, List<EventSourceMappingConfiguration>> getMappings() {
        Map<String, List<EventSourceMappingConfiguration>> byRegion = new TreeMap<>();

        ScrapeConfig scrapeConfig = scrapeConfigProvider.getScrapeConfig();
        scrapeConfig.getLambdaConfig().ifPresent(namespaceConfig -> scrapeConfig.getRegions().forEach(region -> {
            try (LambdaClient client = awsClientProvider.getLambdaClient(region)) {
                // Get all event source mappings
                log.info("Discovering Lambda event source mappings for region={}", region);
                String nextToken = null;
                do {
                    ListEventSourceMappingsRequest req = ListEventSourceMappingsRequest.builder()
                            .marker(nextToken)
                            .build();
                    ListEventSourceMappingsResponse response = client.listEventSourceMappings(req);
                    if (response.hasEventSourceMappings()) {
                        byRegion.computeIfAbsent(region, k -> new ArrayList<>())
                                .addAll(response.eventSourceMappings().stream()
                                        .filter(mapping -> resourceMapper.map(mapping.functionArn()).isPresent())
                                        .collect(Collectors.toList()));

                    }
                    nextToken = response.nextMarker();
                } while (StringUtils.hasText(nextToken));
            } catch (Exception e) {
                log.info("Failed to discover event source mappings", e);
            }
        }));
        return byRegion;
    }

    private void emitMetric(String region, Instant now, Resource functionResource, Resource eventSourceResource) {
        String metricPrefix = metricNameUtil.getMetricPrefix(lambda.getNamespace());
        String metricName = format("%s_event_source", metricPrefix);
        Map<String, String> labels = new TreeMap<>();
        labels.put("region", region);
        labels.put("lambda_function", functionResource.getName());
        eventSourceResource.addLabels(labels, "event_source");
        functionResource.addTagLabels(labels, metricNameUtil);
        gaugeExporter.exportMetric(metricName, HELP_TEXT, labels, now, 1.0D);
    }

    @VisibleForTesting
    Instant now() {
        return Instant.now();
    }
}
