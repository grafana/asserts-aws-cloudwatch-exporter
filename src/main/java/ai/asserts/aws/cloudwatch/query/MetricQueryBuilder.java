/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.cloudwatch.query;

import ai.asserts.aws.config.MetricConfig;
import ai.asserts.aws.resource.Resource;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.cloudwatch.model.Metric;
import software.amazon.awssdk.services.cloudwatch.model.MetricDataQuery;
import software.amazon.awssdk.services.cloudwatch.model.MetricStat;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Component
public class MetricQueryBuilder {
    public List<MetricQuery> buildQueries(QueryIdGenerator queryIdGenerator,
                                          Set<Resource> tagFilteredResources,
                                          MetricConfig metricConfig, Metric metric) {
        List<MetricQuery> metricQueries = new ArrayList<>();
        Optional<Resource> ofResource = tagFilteredResources.stream()
                .filter(resource -> resource.matches(metric))
                .findFirst();
        metricConfig.getStats().forEach(stat -> {
            MetricQuery metricQuery = buildQuery(queryIdGenerator, metricConfig, stat, metric);
            ofResource.ifPresent(metricQuery::setResource);
            metricQueries.add(metricQuery);
        });
        return metricQueries;
    }

    MetricQuery buildQuery(QueryIdGenerator queryIdGenerator, MetricConfig metricConfig,
                           ai.asserts.aws.model.MetricStat stat,
                           Metric metric) {
        MetricStat metricStat = MetricStat.builder()
                .period(metricConfig.getEffectiveScrapeInterval())
                .stat(stat.toString())
                .metric(metric)
                .build();
        return MetricQuery.builder()
                .metricConfig(metricConfig)
                .metric(metric)
                .metricStat(stat)
                .metricDataQuery(MetricDataQuery.builder()
                        .id(queryIdGenerator.next())
                        .metricStat(metricStat)
                        .build())
                .build();
    }
}
