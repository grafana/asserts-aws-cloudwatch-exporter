/*
 * Copyright © 2021
 * Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws;

import ai.asserts.aws.cloudwatch.model.CWNamespace;
import ai.asserts.aws.cloudwatch.model.MetricStat;
import ai.asserts.aws.cloudwatch.query.MetricQuery;
import com.google.common.collect.ImmutableMap;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import software.amazon.awssdk.services.cloudwatch.model.Metric;

import java.util.Map;
import java.util.TreeMap;

import static java.lang.String.format;
import static java.util.stream.Collectors.joining;

@Component
public class MetricNameUtil {
    public static final String SELF_LATENCY_METRIC = "cw_scrape_milliseconds";
    public static final String SELF_OPERATION_LABEL = "operation";
    public static final String SELF_REGION_LABEL = "region";
    public static final String SELF_NAMESPACE_LABEL = "namespace";
    public static final String SELF_INTERVAL_LABEL = "interval";
    public static final String SELF_FUNCTION_NAME_LABEL = "function_name";

    private final Map<String, String> NAMESPACE_TO_METRIC_PREFIX = new ImmutableMap.Builder<String, String>()
            .put(CWNamespace.lambda.getNamespace(), "aws_lambda")
            .put(CWNamespace.sqs.getNamespace(), "aws_sqs")
            .put(CWNamespace.s3.getNamespace(), "aws_s3")
            .put(CWNamespace.dynamodb.getNamespace(), "aws_dynamodb")
            .put(CWNamespace.alb.getNamespace(), "aws_alb")
            .put(CWNamespace.elb.getNamespace(), "aws_elb")
            .put(CWNamespace.ebs.getNamespace(), "aws_ebs")
            .put(CWNamespace.efs.getNamespace(), "aws_efs")
            .put(CWNamespace.kinesis.getNamespace(), "aws_kinesis")
            .put(CWNamespace.ecs_containerinsights.getNamespace(), "aws_ecscontainerinsights")
            .build();

    public String exportedMetricName(Metric metric, MetricStat metricStat) {
        String namespace = metric.namespace();
        String metricPrefix = getMetricPrefix(namespace);
        return format("%s_%s_%s", metricPrefix, toSnakeCase(metric.metricName()),
                metricStat.getShortForm().toLowerCase());

    }

    public String exportedMetric(MetricQuery metricQuery, MetricStat metricStat) {
        Map<String, String> labels = new TreeMap<>();
        metricQuery.getMetric().dimensions().forEach(dimension ->
                labels.put(format("d_%s", toSnakeCase(dimension.name())), dimension.value()));

        if (!CollectionUtils.isEmpty(metricQuery.getResource().getTags())) {
            metricQuery.getResource().getTags().forEach(tag ->
                    labels.put(format("tag_%s", toSnakeCase(tag.key())), tag.value()));
        }

        return format("%s{%s}", exportedMetricName(metricQuery.getMetric(), metricStat),
                labels.entrySet().stream()
                        .map(entry -> format("%s=\"%s\"", entry.getKey(), entry.getValue()))
                        .collect(joining(", ")));
    }

    public String getMetricPrefix(String namespace) {
        return NAMESPACE_TO_METRIC_PREFIX.get(namespace);
    }

    public String toSnakeCase(String input) {
        StringBuilder builder = new StringBuilder();
        boolean lastCaseWasSmall = false;
        int numContiguousUpperCase = 0;
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (Character.isUpperCase(c) && lastCaseWasSmall) {
                builder.append("_");
            } else if (Character.isLowerCase(c) && numContiguousUpperCase > 1) {
                char lastUpperCaseLetter = builder.toString().charAt(builder.length() - 1);
                builder.deleteCharAt(builder.length() - 1);
                builder.append("_");
                builder.append(lastUpperCaseLetter);
            }
            builder.append(c);
            lastCaseWasSmall = Character.isLowerCase(c);
            if (Character.isUpperCase(c)) {
                numContiguousUpperCase++;
            } else {
                numContiguousUpperCase = 0;
            }
        }
        return builder.toString().toLowerCase();
    }
}
