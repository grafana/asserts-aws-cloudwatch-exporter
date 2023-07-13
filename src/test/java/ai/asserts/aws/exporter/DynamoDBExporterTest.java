/*
 *  Copyright © 2022.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.exporter;

import ai.asserts.aws.AWSClientProvider;
import ai.asserts.aws.AWSApiCallRateLimiter;
import ai.asserts.aws.ScrapeConfigProvider;
import ai.asserts.aws.TagUtil;
import ai.asserts.aws.TaskExecutorUtil;
import ai.asserts.aws.TestTaskThreadPool;
import ai.asserts.aws.account.AWSAccount;
import ai.asserts.aws.account.AccountProvider;
import ai.asserts.aws.config.ScrapeConfig;
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
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.ListTablesResponse;
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

@SuppressWarnings("unchecked")
public class DynamoDBExporterTest extends EasyMockSupport {

    public CollectorRegistry collectorRegistry;
    private AWSAccount accountRegion;
    private AWSClientProvider awsClientProvider;
    private AWSApiCallRateLimiter rateLimiter;
    private MetricSampleBuilder sampleBuilder;
    private Collector.MetricFamilySamples.Sample sample;
    private Collector.MetricFamilySamples familySamples;
    private DynamoDbClient dynamoDbClient;

    private ResourceTagHelper resourceTagHelper;
    private TagUtil tagUtil;
    private ScrapeConfigProvider scrapeConfigProvider;
    private ScrapeConfig scrapeConfig;
    private DynamoDBExporter testClass;

    @BeforeEach
    public void setup() {
        accountRegion = new AWSAccount("tenant", "account1", "", "",
                "role", ImmutableSet.of("region1"));
        AccountProvider accountProvider = mock(AccountProvider.class);
        sampleBuilder = mock(MetricSampleBuilder.class);
        sample = mock(Collector.MetricFamilySamples.Sample.class);
        familySamples = mock(Collector.MetricFamilySamples.class);
        awsClientProvider = mock(AWSClientProvider.class);
        rateLimiter = mock(AWSApiCallRateLimiter.class);
        collectorRegistry = mock(CollectorRegistry.class);
        dynamoDbClient = mock(DynamoDbClient.class);
        resourceTagHelper = mock(ResourceTagHelper.class);
        tagUtil = mock(TagUtil.class);
        BasicMetricCollector basicMetricCollector = mock(BasicMetricCollector.class);
        scrapeConfigProvider = mock(ScrapeConfigProvider.class);
        scrapeConfig = mock(ScrapeConfig.class);
        expect(accountProvider.getAccounts()).andReturn(ImmutableSet.of(accountRegion));
        testClass = new DynamoDBExporter(accountProvider, awsClientProvider, collectorRegistry, rateLimiter,
                sampleBuilder, resourceTagHelper, tagUtil, new TaskExecutorUtil(new TestTaskThreadPool(),
                new AWSApiCallRateLimiter(basicMetricCollector, (account) -> "tenant")), scrapeConfigProvider);
    }

    @Test
    public void exporterBucketTest() {

        SortedMap<String, String> labels1 = new TreeMap<>();
        labels1.put("namespace", "AWS/DynamoDB");
        labels1.put("region", "region1");
        labels1.put("name", "b1");
        labels1.put("id", "b1");
        labels1.put("job", "b1");
        labels1.put(SCRAPE_ACCOUNT_ID_LABEL, "account1");
        labels1.put("aws_resource_type", "AWS::DynamoDB::Table");
        labels1.put("tag_k", "v");
        ListTablesResponse response = ListTablesResponse
                .builder()
                .tableNames("b1")
                .build();
        Capture<AWSApiCallRateLimiter.AWSAPICall<ListTablesResponse>> callbackCapture = Capture.newInstance();

        expect(rateLimiter.doWithRateLimit(eq("DynamoDbClient/listTables"),
                anyObject(SortedMap.class), capture(callbackCapture))).andReturn(response);
        expect(awsClientProvider.getDynamoDBClient("region1", accountRegion)).andReturn(dynamoDbClient);
        Tag tag = Tag.builder().key("k").value("v").build();
        expect(resourceTagHelper.getResourcesWithTag(accountRegion, "region1", "dynamodb:table",
                ImmutableList.of("b1")))
                .andReturn(ImmutableMap.of("b1", Resource.builder()
                        .tags(ImmutableList.of(tag))
                        .build()));
        expect(scrapeConfigProvider.getScrapeConfig("tenant")).andReturn(scrapeConfig);
        expect(tagUtil.tagLabels(scrapeConfig, ImmutableList.of(tag))).andReturn(ImmutableMap.of("tag_k", "v"));
        expect(sampleBuilder.buildSingleSample("aws_resource", labels1, 1.0D))
                .andReturn(Optional.of(sample));
        expect(sampleBuilder.buildFamily(ImmutableList.of(sample))).andReturn(Optional.of(familySamples));
        expectLastCall();
        replayAll();
        testClass.update();
        testClass.collect();
        assertEquals(ImmutableList.of(familySamples), testClass.collect());
        verifyAll();
    }
}
