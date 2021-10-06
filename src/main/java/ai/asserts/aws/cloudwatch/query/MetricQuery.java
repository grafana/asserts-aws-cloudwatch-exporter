/*
 * Copyright © 2021
 * Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.cloudwatch.query;

import ai.asserts.aws.cloudwatch.config.MetricConfig;
import ai.asserts.aws.cloudwatch.model.MetricStat;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import software.amazon.awssdk.services.cloudwatch.model.Metric;
import software.amazon.awssdk.services.cloudwatch.model.MetricDataQuery;

@Getter
@EqualsAndHashCode
@ToString
@Builder
public class MetricQuery {
    private final MetricConfig metricConfig;
    private final Metric metric;
    private final MetricStat metricStat;
    private final MetricDataQuery metricDataQuery;
}
