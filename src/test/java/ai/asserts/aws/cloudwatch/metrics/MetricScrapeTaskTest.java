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
import io.prometheus.client.Collector;
import io.prometheus.client.Collector.MetricFamilySamples.Sample;
import org.easymock.EasyMockSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.GetMetricDataRequest;
import software.amazon.awssdk.services.cloudwatch.model.GetMetricDataResponse;
import software.amazon.awssdk.services.cloudwatch.model.MetricDataQuery;
import software.amazon.awssdk.services.cloudwatch.model.MetricDataResult;
import software.amazon.awssdk.services.cloudwatch.model.StatusCode;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static org.easymock.EasyMock.anyObject;
import static org.easymock.EasyMock.anyString;
import static org.easymock.EasyMock.expect;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class MetricScrapeTaskTest extends EasyMockSupport {
    private String region;
    private Integer interval;
    private Integer delay;
    private MetricQueryProvider metricQueryProvider;
    private QueryBatcher queryBatcher;
    private GaugeExporter gaugeExporter;
    private AWSClientProvider awsClientProvider;
    private CloudWatchClient cloudWatchClient;
    private Instant now;
    private MetricSampleBuilder sampleBuilder;
    private Sample sample;
    private Collector.MetricFamilySamples familySamples;
    private MetricScrapeTask testClass;

    @BeforeEach
    public void setup() {
        now = Instant.now();

        region = "region1";
        interval = 60;
        delay = 60;
        metricQueryProvider = mock(MetricQueryProvider.class);
        queryBatcher = mock(QueryBatcher.class);
        gaugeExporter = mock(GaugeExporter.class);
        awsClientProvider = mock(AWSClientProvider.class);
        cloudWatchClient = mock(CloudWatchClient.class);
        sampleBuilder = mock(MetricSampleBuilder.class);
        sample = new Sample("metric1", Collections.emptyList(), Collections.emptyList(),
                1.0D, now.toEpochMilli());
        familySamples = mock(Collector.MetricFamilySamples.class);

        testClass = new MetricScrapeTask(region, interval, delay) {
            @Override
            Instant now() {
                return now;
            }
        };
        testClass.setMetricQueryProvider(metricQueryProvider);
        testClass.setQueryBatcher(queryBatcher);
        testClass.setGaugeExporter(gaugeExporter);
        testClass.setAwsClientProvider(awsClientProvider);
        testClass.setSampleBuilder(sampleBuilder);
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

        Instant endTime = now.minusSeconds(delay);
        Instant startTime = now.minusSeconds(period + delay);
        GetMetricDataRequest request = GetMetricDataRequest.builder()
                .metricDataQueries(queries.stream()
                        .map(MetricQuery::getMetricDataQuery)
                        .collect(Collectors.toList()))
                .endTime(endTime)
                .startTime(startTime)
                .build();

        MetricDataResult mdr1 = MetricDataResult.builder()
                .timestamps(ImmutableList.of(now))
                .values(ImmutableList.of(1.0D))
                .statusCode(StatusCode.COMPLETE)
                .id("id1")
                .build();

        expect(cloudWatchClient.getMetricData(request)).andReturn(
                GetMetricDataResponse.builder()
                        .metricDataResults(ImmutableList.of(mdr1))
                        .nextToken("token1")
                        .build()
        );
        gaugeExporter.exportMetric(anyString(), anyString(), anyObject(), anyObject(), anyObject());

        expect(sampleBuilder.buildSamples(region, queries.get(0), mdr1, period))
                .andReturn(ImmutableList.of(sample));

        request = GetMetricDataRequest.builder()
                .metricDataQueries(queries.stream()
                        .map(MetricQuery::getMetricDataQuery)
                        .collect(Collectors.toList()))
                .endTime(endTime)
                .startTime(startTime)
                .nextToken("token1")
                .build();

        MetricDataResult mdr2 = MetricDataResult.builder()
                .timestamps(ImmutableList.of(now))
                .values(ImmutableList.of(1.0D))
                .statusCode(StatusCode.COMPLETE)
                .id("id2")
                .build();

        expect(cloudWatchClient.getMetricData(request)).andReturn(
                GetMetricDataResponse.builder()
                        .metricDataResults(ImmutableList.of(mdr2))
                        .build()
        );

        gaugeExporter.exportMetric(anyString(), anyString(), anyObject(), anyObject(), anyObject());

        expect(sampleBuilder.buildSamples(region, queries.get(1), mdr2, period))
                .andReturn(ImmutableList.of(sample));

        cloudWatchClient.close();

        expect(sampleBuilder.buildFamily(ImmutableList.of(sample, sample))).andReturn(familySamples);

        replayAll();
        assertEquals(ImmutableList.of(familySamples), testClass.collect());
        verifyAll();
    }

    @Test
    public void run_NoQueriesForRegion() {
        expect(metricQueryProvider.getMetricQueries())
                .andReturn(ImmutableMap.of());

        replayAll();
        assertEquals(ImmutableList.of(), testClass.collect());
        verifyAll();
    }

    @Test
    public void run_NoQueriesForInterval() {
        expect(metricQueryProvider.getMetricQueries())
                .andReturn(ImmutableMap.of(region, ImmutableMap.of()));

        replayAll();
        assertEquals(ImmutableList.of(), testClass.collect());
        verifyAll();
    }
}
