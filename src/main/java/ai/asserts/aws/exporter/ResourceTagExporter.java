/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.exporter;

import ai.asserts.aws.MetricNameUtil;
import ai.asserts.aws.cloudwatch.config.NamespaceConfig;
import ai.asserts.aws.cloudwatch.config.ScrapeConfig;
import ai.asserts.aws.cloudwatch.config.ScrapeConfigProvider;
import ai.asserts.aws.resource.Resource;
import ai.asserts.aws.resource.TagFilterResourceProvider;
import io.prometheus.client.Collector;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.stream.Collectors;

@Component
@AllArgsConstructor
public class ResourceTagExporter extends Collector {
    private final ScrapeConfigProvider scrapeConfigProvider;
    private final TagFilterResourceProvider tagFilterResourceProvider;
    private final MetricNameUtil metricNameUtil;
    private final MetricSampleBuilder metricSampleBuilder;

    @Override
    public List<MetricFamilySamples> collect() {
        ScrapeConfig scrapeConfig = scrapeConfigProvider.getScrapeConfig();
        List<NamespaceConfig> namespaces = scrapeConfig.getNamespaces();
        Set<Resource> allResources = new HashSet<>();
        scrapeConfig.getRegions().forEach(region -> namespaces.forEach(ns ->
                allResources.addAll(tagFilterResourceProvider.getFilteredResources(region, ns))));
        List<MetricFamilySamples.Sample> samples = allResources
                .stream()
                .map(resource -> {
                    SortedMap<String, String> labels = new TreeMap<>();
                    labels.put("region", resource.getRegion());
                    resource.addLabels(labels, "resource");
                    resource.addTagLabels(labels, metricNameUtil);
                    return metricSampleBuilder.buildSingleSample("aws_resource_tags", labels, 1.0D);
                })
                .collect(Collectors.toList());
        if (samples.size() > 0) {
            return Collections.singletonList(metricSampleBuilder.buildFamily(samples));
        } else {
            return Collections.emptyList();
        }
    }
}
