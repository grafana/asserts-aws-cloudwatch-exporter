/*
 * Copyright © 2021
 * Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.cloudwatch.query;

import ai.asserts.aws.AWSClientProvider;
import ai.asserts.aws.MetricNameUtil;
import ai.asserts.aws.cloudwatch.config.MetricConfig;
import ai.asserts.aws.cloudwatch.config.NamespaceConfig;
import ai.asserts.aws.cloudwatch.config.ScrapeConfig;
import ai.asserts.aws.cloudwatch.config.ScrapeConfigProvider;
import ai.asserts.aws.cloudwatch.model.CWNamespace;
import ai.asserts.aws.cloudwatch.model.MetricStat;
import ai.asserts.aws.cloudwatch.prometheus.GaugeExporter;
import ai.asserts.aws.resource.Resource;
import ai.asserts.aws.resource.TagFilterResourceProvider;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import org.easymock.EasyMockSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.ListMetricsRequest;
import software.amazon.awssdk.services.cloudwatch.model.ListMetricsResponse;
import software.amazon.awssdk.services.cloudwatch.model.Metric;

import static ai.asserts.aws.cloudwatch.model.MetricStat.Average;
import static ai.asserts.aws.cloudwatch.model.MetricStat.Sum;
import static org.easymock.EasyMock.anyObject;
import static org.easymock.EasyMock.expect;

public class MetricQueryProviderTest extends EasyMockSupport {
    private ScrapeConfigProvider scrapeConfigProvider;
    private QueryIdGenerator queryIdGenerator;
    private MetricNameUtil metricNameUtil;
    private AWSClientProvider awsClientProvider;
    private CloudWatchClient cloudWatchClient;
    private TagFilterResourceProvider tagFilterResourceProvider;
    private MetricQueryBuilder metricQueryBuilder;
    private Resource resource;
    private Metric metric;
    private NamespaceConfig namespaceConfig;
    private MetricConfig metricConfig;
    private MetricQuery metricQuery;
    private GaugeExporter gaugeExporter;
    private MetricQueryProvider testClass;
    private final CWNamespace _CW_namespace = CWNamespace.lambda;
    private final String metricName = "Invocations";

    @BeforeEach
    public void setup() {
        scrapeConfigProvider = mock(ScrapeConfigProvider.class);
        queryIdGenerator = mock(QueryIdGenerator.class);
        metricNameUtil = mock(MetricNameUtil.class);
        awsClientProvider = mock(AWSClientProvider.class);
        cloudWatchClient = mock(CloudWatchClient.class);
        tagFilterResourceProvider = mock(TagFilterResourceProvider.class);
        metricQueryBuilder = mock(MetricQueryBuilder.class);
        metricQuery = mock(MetricQuery.class);
        resource = mock(Resource.class);
        metricConfig = mock(MetricConfig.class);
        namespaceConfig = mock(NamespaceConfig.class);
        metricQuery = mock(MetricQuery.class);
        gaugeExporter = mock(GaugeExporter.class);
        metric = Metric.builder()
                .namespace(CWNamespace.lambda.getNamespace())
                .metricName(metricName)
                .build();
        testClass = new MetricQueryProvider(scrapeConfigProvider, queryIdGenerator, metricNameUtil,
                awsClientProvider, tagFilterResourceProvider, metricQueryBuilder, gaugeExporter);
    }

    @Test
    void getMetricQueries() {
        ScrapeConfig scrapeConfig = ScrapeConfig.builder()
                .regions(ImmutableSet.of("region1"))
                .namespaces(ImmutableList.of(namespaceConfig))
                .build();

        expect(scrapeConfigProvider.getScrapeConfig()).andReturn(scrapeConfig);
        expect(awsClientProvider.getCloudWatchClient("region1")).andReturn(cloudWatchClient);

        expect(namespaceConfig.hasTagFilters()).andReturn(true).anyTimes();

        expect(tagFilterResourceProvider.getFilteredResources("region1", namespaceConfig))
                .andReturn(ImmutableSet.of(resource));
        expect(resource.matches(metric)).andReturn(true).anyTimes();

        expect(namespaceConfig.getName()).andReturn(_CW_namespace.name()).anyTimes();
        expect(namespaceConfig.getMetrics()).andReturn(ImmutableList.of(metricConfig));

        expect(metricConfig.getName()).andReturn(metricName).anyTimes();
        expect(metricConfig.getPeriod()).andReturn(300).anyTimes();
        expect(metricConfig.getScrapeInterval()).andReturn(60).anyTimes();
        expect(metricConfig.matchesMetric(metric)).andReturn(true).anyTimes();

        ListMetricsResponse listMetricsResponse1 = ListMetricsResponse.builder()
                .metrics(ImmutableList.of(metric))
                .nextToken("token-1")
                .build();
        expect(cloudWatchClient.listMetrics(ListMetricsRequest.builder()
                .namespace(_CW_namespace.getNamespace())
                .build())).andReturn(listMetricsResponse1);
        gaugeExporter.exportMetric(anyObject(), anyObject(), anyObject(), anyObject(), anyObject());

        expect(metricQuery.getMetric()).andReturn(metric).anyTimes();
        expect(metricQuery.getMetricConfig()).andReturn(metricConfig).anyTimes();
        expect(metricQuery.getMetricStat()).andReturn(Sum);
        expectMetricQuery(Sum, "metric_sum");

        ListMetricsResponse listMetricsResponse2 = ListMetricsResponse.builder()
                .metrics(ImmutableList.of(metric))
                .nextToken(null)
                .build();
        expect(cloudWatchClient.listMetrics(ListMetricsRequest.builder()
                .nextToken("token-1")
                .namespace(_CW_namespace.getNamespace())
                .build())).andReturn(listMetricsResponse2);
        gaugeExporter.exportMetric(anyObject(), anyObject(), anyObject(), anyObject(), anyObject());

        expect(metricQuery.getMetricStat()).andReturn(Average);
        expectMetricQuery(Average, "metric_avg");

        replayAll();
        testClass.getMetricQueries();
        verifyAll();
    }

    @Test
    void getMetricQueries_Exception() {
        ScrapeConfig scrapeConfig = ScrapeConfig.builder()
                .regions(ImmutableSet.of("region1"))
                .namespaces(ImmutableList.of(namespaceConfig))
                .build();

        expect(scrapeConfigProvider.getScrapeConfig()).andReturn(scrapeConfig);

        expect(namespaceConfig.hasTagFilters()).andReturn(false).anyTimes();
        expect(tagFilterResourceProvider.getFilteredResources("region1", namespaceConfig))
                .andReturn(ImmutableSet.of());
        expect(awsClientProvider.getCloudWatchClient("region1")).andThrow(new RuntimeException());

        replayAll();
        testClass.getMetricQueries();
        verifyAll();
    }

    private void expectMetricQuery(MetricStat stat, String metricName) {
        expect(metricQueryBuilder.buildQueries(queryIdGenerator, ImmutableSet.of(resource), metricConfig, metric))
                .andReturn(ImmutableList.of(metricQuery));

        expect(metricNameUtil.exportedMetricName(metric, stat)).andReturn(metricName);
    }
}
