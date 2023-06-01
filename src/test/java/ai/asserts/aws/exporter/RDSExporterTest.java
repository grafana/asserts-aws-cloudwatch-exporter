/*
 *  Copyright © 2022.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.exporter;

import ai.asserts.aws.AWSClientProvider;
import ai.asserts.aws.TenantUtil;
import ai.asserts.aws.TestTaskThreadPool;
import ai.asserts.aws.account.AWSAccount;
import ai.asserts.aws.account.AccountProvider;
import ai.asserts.aws.RateLimiter;
import ai.asserts.aws.TagUtil;
import ai.asserts.aws.resource.Resource;
import ai.asserts.aws.resource.ResourceTagHelper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import io.prometheus.client.Collector;
import io.prometheus.client.CollectorRegistry;
import org.easymock.Capture;
import org.easymock.EasyMockSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.rds.RdsClient;
import software.amazon.awssdk.services.rds.model.DBCluster;
import software.amazon.awssdk.services.rds.model.DBInstance;
import software.amazon.awssdk.services.rds.model.DescribeDbClustersResponse;
import software.amazon.awssdk.services.rds.model.DescribeDbInstancesResponse;
import software.amazon.awssdk.services.resourcegroupstaggingapi.model.Tag;

import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;

import static ai.asserts.aws.MetricNameUtil.SCRAPE_ACCOUNT_ID_LABEL;
import static org.easymock.EasyMock.anyObject;
import static org.easymock.EasyMock.capture;
import static org.easymock.EasyMock.eq;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.expectLastCall;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class RDSExporterTest extends EasyMockSupport {

    public CollectorRegistry collectorRegistry;
    private AWSAccount accountRegion;
    private AWSClientProvider awsClientProvider;
    private RateLimiter rateLimiter;
    private MetricSampleBuilder sampleBuilder;
    private Collector.MetricFamilySamples.Sample sample1;
    private Collector.MetricFamilySamples.Sample sample2;
    private Collector.MetricFamilySamples familySamples;
    private RdsClient rdsClient;
    private ResourceTagHelper resourceTagHelper;
    private TagUtil tagUtil;
    private RDSExporter testClass;

    @BeforeEach
    public void setup() {
        accountRegion = new AWSAccount("tenant", "account1", "", "",
                "role", ImmutableSet.of("region1"));
        AccountProvider accountProvider = mock(AccountProvider.class);
        sampleBuilder = mock(MetricSampleBuilder.class);
        sample1 = mock(Collector.MetricFamilySamples.Sample.class);
        sample2 = mock(Collector.MetricFamilySamples.Sample.class);
        familySamples = mock(Collector.MetricFamilySamples.class);
        awsClientProvider = mock(AWSClientProvider.class);
        rateLimiter = mock(RateLimiter.class);
        collectorRegistry = mock(CollectorRegistry.class);
        rdsClient = mock(RdsClient.class);
        resourceTagHelper = mock(ResourceTagHelper.class);
        tagUtil = mock(TagUtil.class);
        BasicMetricCollector metricCollector = mock(BasicMetricCollector.class);
        expect(accountProvider.getAccounts()).andReturn(ImmutableSet.of(accountRegion));
        testClass = new RDSExporter(accountProvider, awsClientProvider, collectorRegistry, rateLimiter, sampleBuilder,
                resourceTagHelper, tagUtil, new TenantUtil(new TestTaskThreadPool(), new RateLimiter(metricCollector)));
    }

    @Test
    public void exporterRDSTest() {

        SortedMap<String, String> labels1 = new TreeMap<>();
        labels1.put("namespace", "AWS/RDS");
        labels1.put("region", "region1");
        labels1.put("name", "cluster1");
        labels1.put("id", "cluster1");
        labels1.put("job", "cluster1");
        labels1.put("tag_k", "v");
        labels1.put(SCRAPE_ACCOUNT_ID_LABEL, "account1");
        labels1.put("aws_resource_type", "AWS::RDS::DBCluster");

        SortedMap<String, String> labels2 = new TreeMap<>();
        labels2.put("namespace", "AWS/RDS");
        labels2.put("region", "region1");
        labels2.put("name", "db1");
        labels2.put("id", "db1");
        labels2.put("job", "db1");
        labels2.put("tag_k", "v");
        labels2.put(SCRAPE_ACCOUNT_ID_LABEL, "account1");
        labels2.put("aws_resource_type", "AWS::RDS::DBInstance");

        DescribeDbClustersResponse responseCluster = DescribeDbClustersResponse
                .builder()
                .dbClusters(DBCluster.builder().dbClusterIdentifier("cluster1").build())
                .build();
        Capture<RateLimiter.AWSAPICall<DescribeDbClustersResponse>> callbackCapture1 = Capture.newInstance();

        DescribeDbInstancesResponse responseInstance = DescribeDbInstancesResponse
                .builder()
                .dbInstances(DBInstance.builder().dbInstanceIdentifier("db1").build())
                .build();
        Capture<RateLimiter.AWSAPICall<DescribeDbInstancesResponse>> callbackCapture2 = Capture.newInstance();


        expect(rateLimiter.doWithRateLimit(eq("RdsClient/describeDBClusters"),
                anyObject(SortedMap.class), capture(callbackCapture1))).andReturn(responseCluster);
        expect(rateLimiter.doWithRateLimit(eq("RdsClient/describeDBInstances"),
                anyObject(SortedMap.class), capture(callbackCapture2))).andReturn(responseInstance);
        ImmutableList<Tag> tags = ImmutableList.of(Tag.builder().key("k").value("v").build());
        expect(resourceTagHelper.getResourcesWithTag(accountRegion, "region1", "rds:cluster", ImmutableList.of(
                "cluster1")))
                .andReturn(ImmutableMap.of("cluster1", Resource.builder()
                        .tags(tags)
                        .build()));
        expect(resourceTagHelper.getResourcesWithTag(accountRegion, "region1", "rds:db", ImmutableList.of(
                "db1")))
                .andReturn(ImmutableMap.of("db1", Resource.builder()
                        .tags(tags)
                        .build()));
        expect(tagUtil.tagLabels(tags)).andReturn(ImmutableMap.of("tag_k", "v")).times(2);
        expect(awsClientProvider.getRDSClient("region1", accountRegion)).andReturn(rdsClient);
        expect(sampleBuilder.buildSingleSample("aws_resource", labels1, 1.0D))
                .andReturn(Optional.of(sample1));
        expect(sampleBuilder.buildSingleSample("aws_resource", labels2, 1.0D))
                .andReturn(Optional.of(sample2));
        expect(sampleBuilder.buildFamily(ImmutableList.of(sample1, sample2))).andReturn(Optional.of(familySamples));
        expectLastCall();
        replayAll();
        testClass.update();
        testClass.collect();
        assertEquals(ImmutableList.of(familySamples), testClass.collect());
        verifyAll();
    }
}
