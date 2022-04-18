/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.cloudwatch.query;

import ai.asserts.aws.config.MetricConfig;
import ai.asserts.aws.config.NamespaceConfig;
import ai.asserts.aws.resource.Resource;
import com.google.common.collect.ImmutableSet;
import org.easymock.EasyMockSupport;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.cloudwatch.model.Metric;
import software.amazon.awssdk.services.cloudwatch.model.MetricDataQuery;
import software.amazon.awssdk.services.cloudwatch.model.MetricStat;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;

import static ai.asserts.aws.model.MetricStat.Average;
import static ai.asserts.aws.model.MetricStat.Maximum;
import static org.easymock.EasyMock.expect;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class MetricQueryBuilderTest extends EasyMockSupport {
    @Test
    void buildQueries() {
        QueryIdGenerator queryIdGenerator = mock(QueryIdGenerator.class);
        NamespaceConfig namespaceConfig = mock(NamespaceConfig.class);
        Resource resource = mock(Resource.class);

        MetricQueryBuilder metricQueryBuilder = new MetricQueryBuilder();

        expect(namespaceConfig.getEffectiveScrapeInterval()).andReturn(300).anyTimes();

        expect(queryIdGenerator.next()).andReturn("q1");
        expect(queryIdGenerator.next()).andReturn("q2");

        MetricConfig metricConfig = MetricConfig.builder()
                .namespace(namespaceConfig)
                .name("metric")
                .stats(new LinkedHashSet<>(Arrays.asList(Average, Maximum)))
                .build();

        Metric metric = Metric.builder()
                .metricName("metric")
                .build();

        ImmutableSet<Resource> tagFilteredResources = ImmutableSet.of(resource);

        expect(resource.matches(metric)).andReturn(true);

        replayAll();
        List<MetricQuery> metricQueries = metricQueryBuilder.buildQueries(queryIdGenerator, tagFilteredResources,
                metricConfig,
                metric);

        assertEquals(2, metricQueries.size());

        assertEquals(metric, metricQueries.get(0).getMetric());
        assertEquals(metricConfig, metricQueries.get(0).getMetricConfig());
        assertEquals(Average, metricQueries.get(0).getMetricStat());
        assertEquals(MetricDataQuery.builder()
                .metricStat(MetricStat.builder()
                        .stat(Average.name())
                        .metric(metric)
                        .period(300)
                        .build())
                .id("q1")
                .build(), metricQueries.get(0).getMetricDataQuery());
        assertEquals(resource, metricQueries.get(0).getResource());

        assertEquals(metric, metricQueries.get(1).getMetric());
        assertEquals(metricConfig, metricQueries.get(1).getMetricConfig());
        assertEquals(Maximum, metricQueries.get(1).getMetricStat());
        assertEquals(MetricDataQuery.builder()
                .metricStat(MetricStat.builder()
                        .stat(Maximum.name())
                        .metric(metric)
                        .period(300)
                        .build())
                .id("q2")
                .build(), metricQueries.get(1).getMetricDataQuery());
        assertEquals(resource, metricQueries.get(1).getResource());


        verifyAll();
    }
}
