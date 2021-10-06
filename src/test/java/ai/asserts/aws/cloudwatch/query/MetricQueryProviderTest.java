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
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import org.easymock.EasyMockSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.Dimension;
import software.amazon.awssdk.services.cloudwatch.model.ListMetricsRequest;
import software.amazon.awssdk.services.cloudwatch.model.ListMetricsResponse;
import software.amazon.awssdk.services.cloudwatch.model.Metric;

import static ai.asserts.aws.cloudwatch.model.MetricStat.Average;
import static ai.asserts.aws.cloudwatch.model.MetricStat.Sum;
import static org.easymock.EasyMock.expect;

public class MetricQueryProviderTest extends EasyMockSupport {
    private ScrapeConfigProvider scrapeConfigProvider;
    private QueryIdGenerator queryIdGenerator;
    private MetricNameUtil metricNameUtil;
    private AWSClientProvider awsClientProvider;
    private CloudWatchClient cloudWatchClient;
    private MetricQueryProvider testClass;

    @BeforeEach
    public void setup() {
        scrapeConfigProvider = mock(ScrapeConfigProvider.class);
        queryIdGenerator = mock(QueryIdGenerator.class);
        metricNameUtil = mock(MetricNameUtil.class);
        awsClientProvider = mock(AWSClientProvider.class);
        cloudWatchClient = mock(CloudWatchClient.class);
        testClass = new MetricQueryProvider(scrapeConfigProvider, queryIdGenerator, metricNameUtil,
                awsClientProvider);
    }

    @Test
    void getMetricQueries() {
        expect(scrapeConfigProvider.getScrapeConfig()).andReturn(
                ScrapeConfig.builder()
                        .regions(ImmutableSet.of("region1"))
                        .namespaces(ImmutableList.of(
                                NamespaceConfig.builder()
                                        .name("AWS/Lambda")
                                        .metrics(ImmutableList.of(
                                                MetricConfig.builder()
                                                        .name("Invocations")
                                                        .period(300)
                                                        .scrapeInterval(60)
                                                        .namespace(NamespaceConfig.builder()
                                                                .name(CWNamespace.lambda.getNamespace())
                                                                .build())
                                                        .stats(ImmutableSet.of(Sum, Average))
                                                        .build()
                                        ))
                                        .build()
                        ))
                        .build()
        );

        expect(awsClientProvider.getCloudWatchClient("region1")).andReturn(cloudWatchClient);

        Metric metric1 = Metric.builder()
                .metricName("Invocations")
                .dimensions(ImmutableList.of(Dimension.builder()
                        .name("FunctionName")
                        .value("function-1")
                        .build()))
                .build();

        Metric metric2 = Metric.builder()
                .metricName("Errors")
                .dimensions(ImmutableList.of(Dimension.builder()
                        .name("FunctionName")
                        .value("function-1")
                        .build()))
                .build();

        ListMetricsResponse listMetricsResponse1 = ListMetricsResponse.builder()
                .metrics(ImmutableList.of(metric1))
                .nextToken("token-1")
                .build();
        expect(cloudWatchClient.listMetrics(ListMetricsRequest.builder()
                .namespace("AWS/Lambda")
                .build())).andReturn(listMetricsResponse1);

        expect(metricNameUtil.exportedMetric(metric1, Sum)).andReturn("foo_bar1");
        expect(metricNameUtil.exportedMetric(metric1, Average)).andReturn("foo_bar2");
        expect(queryIdGenerator.next()).andReturn("q1");
        expect(queryIdGenerator.next()).andReturn("q2");

        ListMetricsResponse listMetricsResponse2 = ListMetricsResponse.builder()
                .metrics(ImmutableList.of(metric2))
                .nextToken(null)
                .build();
        expect(cloudWatchClient.listMetrics(ListMetricsRequest.builder()
                .nextToken("token-1")
                .namespace("AWS/Lambda")
                .build())).andReturn(listMetricsResponse2);

        replayAll();
        testClass.getMetricQueries();
        verifyAll();
    }
}
