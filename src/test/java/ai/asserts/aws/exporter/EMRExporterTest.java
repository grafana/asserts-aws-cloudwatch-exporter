/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.exporter;

import ai.asserts.aws.AWSClientProvider;
import ai.asserts.aws.TaskExecutorUtil;
import ai.asserts.aws.TestTaskThreadPool;
import ai.asserts.aws.account.AccountProvider;
import ai.asserts.aws.AWSApiCallRateLimiter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import io.prometheus.client.Collector.MetricFamilySamples;
import io.prometheus.client.Collector.MetricFamilySamples.Sample;
import io.prometheus.client.CollectorRegistry;
import org.easymock.EasyMockSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.emr.EmrClient;
import software.amazon.awssdk.services.emr.model.ClusterState;
import software.amazon.awssdk.services.emr.model.ClusterStatus;
import software.amazon.awssdk.services.emr.model.ClusterSummary;
import software.amazon.awssdk.services.emr.model.ListClustersResponse;

import java.util.Map;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;

import ai.asserts.aws.account.AWSAccount;

import static ai.asserts.aws.MetricNameUtil.SCRAPE_ACCOUNT_ID_LABEL;
import static ai.asserts.aws.MetricNameUtil.SCRAPE_REGION_LABEL;
import static org.easymock.EasyMock.anyDouble;
import static org.easymock.EasyMock.anyObject;
import static org.easymock.EasyMock.eq;
import static org.easymock.EasyMock.expect;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class EMRExporterTest extends EasyMockSupport {
    private AccountProvider accountProvider;
    private AWSClientProvider awsClientProvider;
    private EmrClient emrClient;
    private CollectorRegistry collectorRegistry;
    private MetricSampleBuilder metricSampleBuilder;
    private BasicMetricCollector basicMetricCollector;
    private MetricFamilySamples metricFamilySamples;
    private AWSAccount account;
    private Sample sample;
    private EMRExporter emrExporter;

    @BeforeEach
    public void setup() {
        account = AWSAccount.builder()
                .tenant("acme")
                .accountId("account-id")
                .regions(ImmutableSet.of("region"))
                .build();
        accountProvider = mock(AccountProvider.class);
        awsClientProvider = mock(AWSClientProvider.class);
        collectorRegistry = mock(CollectorRegistry.class);
        basicMetricCollector = mock(BasicMetricCollector.class);
        metricSampleBuilder = mock(MetricSampleBuilder.class);
        metricFamilySamples = mock(MetricFamilySamples.class);
        sample = mock(Sample.class);
        emrClient = mock(EmrClient.class);
        emrExporter = new EMRExporter(accountProvider, awsClientProvider, collectorRegistry,
                new AWSApiCallRateLimiter(basicMetricCollector, (account) -> "acme"), metricSampleBuilder,
                new TaskExecutorUtil(new TestTaskThreadPool(), new AWSApiCallRateLimiter(basicMetricCollector,
                        (account) -> "acme")));
    }

    @Test
    public void afterPropertiesSet() throws Exception {
        collectorRegistry.register(emrExporter);
        replayAll();
        emrExporter.afterPropertiesSet();
        verifyAll();
    }

    @Test
    @SuppressWarnings("unchecked")
    public void update() {
        ImmutableMap<String, String> baseLabels = ImmutableMap.of(
                SCRAPE_ACCOUNT_ID_LABEL, "account-id", SCRAPE_REGION_LABEL, "region",
                "namespace", "AWS/ElasticMapReduce", "aws_resource_type", "AWS::ElasticMapReduce::Cluster"
        );
        Map<String, String> labels = new TreeMap<>(baseLabels);
        labels.put("job", "id1");
        labels.put("name", "name1");
        labels.put("job_flow_id", "id1");


        expect(accountProvider.getAccounts()).andReturn(ImmutableSet.of(account)).anyTimes();
        expect(awsClientProvider.getEmrClient("region", account)).andReturn(emrClient);
        expect(emrClient.listClusters()).andReturn(ListClustersResponse.builder()
                .clusters(
                        ClusterSummary.builder().id("id1").name("name1").status(ClusterStatus.builder()
                                .state(ClusterState.RUNNING)
                                .build()).build(),
                        ClusterSummary.builder().id("id2").name("name2").status(ClusterStatus.builder()
                                .state(ClusterState.TERMINATED)
                                .build()).build())
                .build());
        basicMetricCollector.recordLatency(eq("aws_exporter_milliseconds"), anyObject(SortedMap.class), anyDouble());

        expect(metricSampleBuilder.buildSingleSample("aws_resource", labels, 1.0D))
                .andReturn(Optional.of(sample));
        expect(metricSampleBuilder.buildFamily(ImmutableList.of(sample))).andReturn(Optional.of(metricFamilySamples));

        replayAll();
        emrExporter.update();
        verifyAll();

        assertEquals(ImmutableList.of(metricFamilySamples), emrExporter.collect());
    }
}
