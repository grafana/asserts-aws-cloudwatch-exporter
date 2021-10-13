/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.cloudwatch.prometheus;

import ai.asserts.aws.MetricNameUtil;
import ai.asserts.aws.cloudwatch.query.MetricQuery;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.util.Map;
import java.util.TreeMap;

import static java.lang.String.format;

@Component
@AllArgsConstructor
public class LabelBuilder {
    private MetricNameUtil metricNameUtil;

    Map<String, String> buildLabels(String region, MetricQuery metricQuery) {
        Map<String, String> labels = new TreeMap<>();
        labels.put("region", region);
        metricQuery.getMetric().dimensions().forEach(dimension -> {
            String key = format("d_%s", metricNameUtil.toSnakeCase(dimension.name()));
            labels.put(key, dimension.value());
        });

        if (metricQuery.getResource() != null && !CollectionUtils.isEmpty(metricQuery.getResource().getTags())) {
            metricQuery.getResource().getTags().forEach(tag ->
                    labels.put(format("tag_%s", metricNameUtil.toSnakeCase(tag.key())), tag.value()));
        }
        return labels;
    }
}
