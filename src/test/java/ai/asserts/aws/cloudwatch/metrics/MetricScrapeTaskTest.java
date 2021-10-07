/*
 * Copyright © 2021
 * Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.cloudwatch.metrics;

import ai.asserts.aws.AWSClientProvider;
import ai.asserts.aws.cloudwatch.config.MetricConfig;
import ai.asserts.aws.cloudwatch.prometheus.GaugeExporter;
import ai.asserts.aws.cloudwatch.query.MetricQuery;
import ai.asserts.aws.cloudwatch.query.MetricQueryProvider;
import ai.asserts.aws.cloudwatch.query.QueryBatcher;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;
import org.easymock.EasyMockSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.GetMetricDataRequest;
import software.amazon.awssdk.services.cloudwatch.model.GetMetricDataResponse;
import software.amazon.awssdk.services.cloudwatch.model.MetricDataQuery;
import software.amazon.awssdk.services.cloudwatch.model.MetricDataResult;

import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

import static org.easymock.EasyMock.anyObject;
import static org.easymock.EasyMock.anyString;
import static org.easymock.EasyMock.expect;

public class MetricScrapeTaskTest extends EasyMockSupport {
    private String region;
    private Integer interval;
    private MetricQueryProvider metricQueryProvider;
    private QueryBatcher queryBatcher;
    private GaugeExporter gaugeExporter;
    private AWSClientProvider awsClientProvider;
    private CloudWatchClient cloudWatchClient;
    private Instant now;
    private MetricScrapeTask testClass;

    @BeforeEach
    public void setup() {
        region = "region1";
        interval = 60;
        metricQueryProvider = mock(MetricQueryProvider.class);
        queryBatcher = mock(QueryBatcher.class);
        gaugeExporter = mock(GaugeExporter.class);
        awsClientProvider = mock(AWSClientProvider.class);
        cloudWatchClient = mock(CloudWatchClient.class);

        now = Instant.now();
        testClass = new MetricScrapeTask(region, interval) {
            @Override
            Instant now() {
                return now;
            }
        };
        testClass.setMetricQueryProvider(metricQueryProvider);
        testClass.setQueryBatcher(queryBatcher);
        testClass.setGaugeExporter(gaugeExporter);
        testClass.setAwsClientProvider(awsClientProvider);
    }

    @Test
    public void run() {
        int period = 300;
        List<MetricQuery> queries = ImmutableList.of(
                MetricQuery.builder()
                        .metricConfig(MetricConfig.builder().period(period).scrapeInterval(interval).build())
                        .metricDataQuery(MetricDataQuery.builder()
                                .id("id1")
                                .build())
                        .build(),
                MetricQuery.builder()
                        .metricConfig(MetricConfig.builder().period(period).scrapeInterval(interval).build())
                        .metricDataQuery(MetricDataQuery.builder()
                                .id("id2")
                                .build())
                        .build(),
                MetricQuery.builder()
                        .metricConfig(MetricConfig.builder().period(period).scrapeInterval(interval).build())
                        .metricDataQuery(MetricDataQuery.builder()
                                .id("id3")
                                .build())
                        .build());

        expect(metricQueryProvider.getMetricQueries())
                .andReturn(ImmutableMap.of(region, ImmutableMap.of(interval, queries)));

        expect(awsClientProvider.getCloudWatchClient(region)).andReturn(cloudWatchClient);
        expect(queryBatcher.splitIntoBatches(queries)).andReturn(ImmutableList.of(queries));

        GetMetricDataRequest request = GetMetricDataRequest.builder()
                .metricDataQueries(queries.stream()
                        .map(MetricQuery::getMetricDataQuery)
                        .collect(Collectors.toList()))
                .endTime(now.minusSeconds(60))
                .startTime(now.minusSeconds(period + 60))
                .build();

        MetricDataResult mdr1 = MetricDataResult.builder()
                .timestamps(ImmutableList.of(now))
                .values(ImmutableList.of(1.0D))
                .id("id1")
                .build();

        expect(cloudWatchClient.getMetricData(request)).andReturn(
                GetMetricDataResponse.builder()
                        .metricDataResults(ImmutableList.of(mdr1))
                        .nextToken("token1")
                        .build()
        );
        gaugeExporter.exportMetric(anyString(),anyString(),anyObject(),anyObject(),anyObject());
        gaugeExporter.exportMetricMeta(region, queries.get(0));
        gaugeExporter.exportMetrics(region, queries.get(0), period, mdr1);

        request = GetMetricDataRequest.builder()
                .metricDataQueries(queries.stream()
                        .map(MetricQuery::getMetricDataQuery)
                        .collect(Collectors.toList()))
                .endTime(now.minusSeconds(60))
                .startTime(now.minusSeconds(period + 60))
                .nextToken("token1")
                .build();

        MetricDataResult mdr2 = MetricDataResult.builder()
                .timestamps(ImmutableList.of(now))
                .values(ImmutableList.of(1.0D))
                .id("id2")
                .build();

        expect(cloudWatchClient.getMetricData(request)).andReturn(
                GetMetricDataResponse.builder()
                        .metricDataResults(ImmutableList.of(mdr2))
                        .build()
        );
        gaugeExporter.exportMetric(anyString(),anyString(),anyObject(),anyObject(),anyObject());
        gaugeExporter.exportMetricMeta(region, queries.get(1));
        gaugeExporter.exportMetrics(region, queries.get(1), period, mdr2);

        gaugeExporter.exportZeros(region, now.minusSeconds(period + 60), now.minusSeconds(60), period,
                ImmutableSortedMap.of("id3", queries.get(2)));

        replayAll();
        testClass.run();
        verifyAll();
    }

    @Test
    public void run_NoQueriesForRegion() {
        expect(metricQueryProvider.getMetricQueries())
                .andReturn(ImmutableMap.of());

        replayAll();
        testClass.run();
        verifyAll();
    }

    @Test
    public void run_NoQueriesForInterval() {
        expect(metricQueryProvider.getMetricQueries())
                .andReturn(ImmutableMap.of(region, ImmutableMap.of()));

        replayAll();
        testClass.run();
        verifyAll();
    }
}
