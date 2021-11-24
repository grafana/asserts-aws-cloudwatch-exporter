/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.exporter;

import ai.asserts.aws.MetricNameUtil;
import ai.asserts.aws.cloudwatch.config.ScrapeConfigProvider;
import ai.asserts.aws.cloudwatch.query.MetricQuery;
import ai.asserts.aws.lambda.LambdaLabelConverter;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.cloudwatch.model.Dimension;
import software.amazon.awssdk.services.cloudwatch.model.Metric;

import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import static java.lang.String.format;

@Component
@AllArgsConstructor
public class LabelBuilder {
    private final ScrapeConfigProvider scrapeConfigProvider;
    private final MetricNameUtil metricNameUtil;
    private final LambdaLabelConverter lambdaLabelConverter;

    public Map<String, String> buildLabels(String region, MetricQuery metricQuery) {
        Map<String, String> labels = new TreeMap<>();
        labels.put("region", region);
        scrapeConfigProvider.getStandardNamespace(metricQuery.getMetric().namespace())
                .ifPresent(ns -> labels.put("namespace", ns.getNormalizedNamespace()));


        if (lambdaLabelConverter.shouldUseForNamespace(metricQuery.getMetric().namespace())) {
            metricQuery.getMetric().dimensions().forEach(dimension ->
                    labels.putAll(lambdaLabelConverter.convert(dimension)));
        } else {
            metricQuery.getMetric().dimensions().forEach(dimension -> {
                String key = format("d_%s", metricNameUtil.toSnakeCase(dimension.name()));
                labels.put(key, dimension.value());
            });
        }

        if (metricQuery.getResource() != null) {
            metricQuery.getResource().addTagLabels(labels, metricNameUtil);
        }
        getJob(metricQuery.getMetric()).ifPresent(jobName -> labels.put("job", jobName));
        getTopic(metricQuery.getMetric()).ifPresent(queueName -> labels.put("topic", queueName));

        return labels;
    }

    Optional<String> getJob(Metric metric) {
        if (metric.hasDimensions()) {
            if ("AWS/Lambda".equals(metric.namespace())) {
                return metric.dimensions().stream()
                        .filter(dimension -> dimension.name().equals("FunctionName"))
                        .map(Dimension::value)
                        .findFirst();
            } else if ("LambdaInsights".equals(metric.namespace())) {
                return metric.dimensions().stream()
                        .filter(dimension ->
                                dimension.name().equals("function_name") || dimension.name().equals("FunctionName"))
                        .map(Dimension::value)
                        .findFirst();
            } else if ("AWS/ECS".equals(metric.namespace()) || "ECS/ContainerInsights".equals(metric.namespace())) {
                return metric.dimensions().stream()
                        .filter(dimension -> dimension.name().equals("ServiceName"))
                        .map(Dimension::value)
                        .findFirst();
            }
        }
        return Optional.empty();
    }

    Optional<String> getTopic(Metric metric) {
        if (metric.hasDimensions()) {
            if ("AWS/SQS".equals(metric.namespace())) {
                return metric.dimensions().stream()
                        .filter(dimension -> dimension.name().equals("QueueName"))
                        .map(Dimension::value)
                        .findFirst();
            }
        }
        return Optional.empty();
    }
}
