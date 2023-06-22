/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.exporter;

import ai.asserts.aws.MetricNameUtil;
import ai.asserts.aws.ScrapeConfigProvider;
import ai.asserts.aws.cloudwatch.query.MetricQuery;
import ai.asserts.aws.lambda.LambdaLabelConverter;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.TreeMap;

import static ai.asserts.aws.MetricNameUtil.SCRAPE_ACCOUNT_ID_LABEL;
import static ai.asserts.aws.MetricNameUtil.SCRAPE_REGION_LABEL;
import static java.lang.String.format;

@Component
@AllArgsConstructor
public class LabelBuilder {
    private final ScrapeConfigProvider scrapeConfigProvider;
    private final MetricNameUtil metricNameUtil;
    private final LambdaLabelConverter lambdaLabelConverter;

    public Map<String, String> buildLabels(String account, String region, MetricQuery metricQuery) {
        Map<String, String> labels = new TreeMap<>();
        labels.put(SCRAPE_ACCOUNT_ID_LABEL, account);
        labels.put(SCRAPE_REGION_LABEL, region);
        String namespace = metricQuery.getMetric().namespace();
        scrapeConfigProvider.getStandardNamespace(namespace)
                .ifPresent(ns -> {
                    labels.put("cw_namespace", ns.getNormalizedNamespace());
                    labels.put("namespace", ns.getNormalizedNamespace());
                });


        if (lambdaLabelConverter.shouldUseForNamespace(namespace)) {
            metricQuery.getMetric().dimensions().forEach(dimension ->
                    labels.putAll(lambdaLabelConverter.convert(dimension)));
        } else {
            metricQuery.getMetric().dimensions().forEach(dimension -> {
                String key = format("d_%s", metricNameUtil.toSnakeCase(dimension.name()));
                labels.put(key, dimension.value());
            });
        }

        if (metricQuery.getResource() != null) {
            metricQuery.getResource().addEnvLabel(labels, metricNameUtil);
        }
        return labels;
    }
}
