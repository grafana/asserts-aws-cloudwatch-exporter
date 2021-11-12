/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.exporter;

import ai.asserts.aws.MetricNameUtil;
import ai.asserts.aws.resource.TagFilterResourceProvider;
import io.prometheus.client.Collector;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.stream.Collectors;

@Component
@AllArgsConstructor
public class ResourceTagExporter extends Collector {
    private final TagFilterResourceProvider tagFilterResourceProvider;
    private final MetricNameUtil metricNameUtil;
    private final MetricSampleBuilder metricSampleBuilder;

    @Override
    public List<MetricFamilySamples> collect() {
        List<MetricFamilySamples.Sample> copy = tagFilterResourceProvider.getResourceCache().asMap()
                .values().stream().flatMap(Collection::stream)
                .map(resource -> {
                    SortedMap<String, String> labels = new TreeMap<>();
                    labels.put("region", resource.getRegion());
                    resource.addLabels(labels, "resource");
                    resource.addTagLabels(labels, metricNameUtil);
                    return metricSampleBuilder.buildSingleSample("aws_resource_tags", labels, 1.0D);
                })
                .collect(Collectors.toList());
        return Collections.singletonList(new MetricFamilySamples("aws_resource_tags", Type.GAUGE, "", copy));
    }
}
