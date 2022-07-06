/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.exporter;

import ai.asserts.aws.AWSClientProvider;
import ai.asserts.aws.AccountProvider;
import ai.asserts.aws.AccountProvider.AWSAccount;
import ai.asserts.aws.MetricNameUtil;
import ai.asserts.aws.RateLimiter;
import ai.asserts.aws.ScrapeConfigProvider;
import ai.asserts.aws.config.ScrapeConfig;
import ai.asserts.aws.config.TagExportConfig;
import ai.asserts.aws.resource.Resource;
import ai.asserts.aws.resource.ResourceMapper;
import ai.asserts.aws.resource.ResourceTagHelper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import io.prometheus.client.Collector;
import org.easymock.Capture;
import org.easymock.EasyMockSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.config.ConfigClient;
import software.amazon.awssdk.services.config.model.ListDiscoveredResourcesRequest;
import software.amazon.awssdk.services.config.model.ListDiscoveredResourcesResponse;
import software.amazon.awssdk.services.config.model.ResourceIdentifier;

import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;

import static ai.asserts.aws.MetricNameUtil.SCRAPE_ACCOUNT_ID_LABEL;
import static ai.asserts.aws.MetricNameUtil.SCRAPE_REGION_LABEL;
import static ai.asserts.aws.resource.ResourceType.AutoScalingGroup;
import static ai.asserts.aws.resource.ResourceType.ECSService;
import static ai.asserts.aws.resource.ResourceType.LoadBalancer;
import static org.easymock.EasyMock.anyObject;
import static org.easymock.EasyMock.anyString;
import static org.easymock.EasyMock.capture;
import static org.easymock.EasyMock.eq;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.expectLastCall;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static software.amazon.awssdk.services.config.model.ResourceType.AWS_DYNAMO_DB_TABLE;
import static software.amazon.awssdk.services.config.model.ResourceType.AWS_S3_BUCKET;

@SuppressWarnings("unchecked")
public class ResourceExporterTest extends EasyMockSupport {
    private AWSAccount account;
    private AccountProvider accountProvider;
    private ScrapeConfigProvider scrapeConfigProvider;
    private ScrapeConfig scrapeConfig;
    private TagExportConfig tagExportConfig;
    private AWSClientProvider awsClientProvider;
    private ConfigClient configClient;
    private RateLimiter rateLimiter;
    private Collector.MetricFamilySamples.Sample sample;
    private Collector.MetricFamilySamples metricFamilySamples;
    private MetricSampleBuilder metricSampleBuilder;
    private ResourceMapper resourceMapper;
    private Resource resource;
    private MetricNameUtil metricNameUtil;
    private ResourceTagHelper resourceTagHelper;
    private ResourceExporter testClass;

    @BeforeEach
    public void setup() {
        account = new AWSAccount("account", "", "", "role",
                ImmutableSet.of("region"));
        accountProvider = mock(AccountProvider.class);
        scrapeConfigProvider = mock(ScrapeConfigProvider.class);
        scrapeConfig = mock(ScrapeConfig.class);
        tagExportConfig = mock(TagExportConfig.class);
        awsClientProvider = mock(AWSClientProvider.class);
        configClient = mock(ConfigClient.class);
        rateLimiter = mock(RateLimiter.class);
        metricSampleBuilder = mock(MetricSampleBuilder.class);
        sample = mock(Collector.MetricFamilySamples.Sample.class);
        metricFamilySamples = mock(Collector.MetricFamilySamples.class);
        resourceMapper = mock(ResourceMapper.class);
        metricNameUtil = mock(MetricNameUtil.class);
        resourceTagHelper = mock(ResourceTagHelper.class);
        resource = mock(Resource.class);
        testClass = new ResourceExporter(accountProvider, scrapeConfigProvider, awsClientProvider, rateLimiter,
                metricSampleBuilder, resourceMapper, metricNameUtil, resourceTagHelper);
    }

    @Test
    public void update() {
        expect(accountProvider.getAccounts()).andReturn(ImmutableSet.of(account));
        expect(scrapeConfigProvider.getScrapeConfig()).andReturn(scrapeConfig);
        expect(scrapeConfig.getDiscoverResourceTypes()).andReturn(ImmutableSet.of("type1")).anyTimes();
        expect(scrapeConfig.getTagExportConfig()).andReturn(tagExportConfig).anyTimes();
        expect(awsClientProvider.getConfigClient("region", account)).andReturn(configClient).anyTimes();

        Capture<RateLimiter.AWSAPICall<ListDiscoveredResourcesResponse>> callbackCapture = Capture.newInstance();

        ListDiscoveredResourcesResponse response = ListDiscoveredResourcesResponse.builder()
                .resourceIdentifiers(
                        ResourceIdentifier.builder()
                                .resourceName("TableName")
                                .resourceType(AWS_DYNAMO_DB_TABLE)
                                .build(),
                        ResourceIdentifier.builder()
                                .resourceName("BucketName")
                                .resourceType(AWS_S3_BUCKET)
                                .build())
                .build();

        expect(configClient.listDiscoveredResources(ListDiscoveredResourcesRequest.builder()
                .includeDeletedResources(false)
                .resourceType("type1")
                .build())).andReturn(response);
        expect(resourceTagHelper.getResourcesWithTag(account, "region", "type1", ImmutableList.of(
                "TableName", "BucketName"
        ))).andReturn(ImmutableMap.of());
        expect(resourceMapper.map(anyString())).andReturn(Optional.empty()).anyTimes();

        expect(rateLimiter.doWithRateLimit(eq("ConfigClient/listDiscoveredResources"),
                anyObject(SortedMap.class), capture(callbackCapture))).andReturn(response);

        expect(metricSampleBuilder.buildSingleSample("aws_resource",
                ImmutableSortedMap.of(
                        SCRAPE_REGION_LABEL, "region",
                        "aws_resource_type", "AWS::DynamoDB::Table",
                        "name", "TableName",
                        "job", "TableName",
                        SCRAPE_ACCOUNT_ID_LABEL, "account"
                ),
                1.0D))
                .andReturn(sample);
        expect(metricSampleBuilder.buildSingleSample("aws_resource",
                ImmutableSortedMap.of(
                        SCRAPE_REGION_LABEL, "region",
                        "aws_resource_type", "AWS::S3::Bucket",
                        "name", "BucketName",
                        "job", "BucketName",
                        SCRAPE_ACCOUNT_ID_LABEL, "account"
                ),
                1.0D))
                .andReturn(sample);

        expect(metricSampleBuilder.buildFamily(ImmutableList.of(sample, sample))).andReturn(metricFamilySamples);
        replayAll();
        testClass.update();

        assertEquals(response, callbackCapture.getValue().makeCall());

        assertEquals(ImmutableList.of(metricFamilySamples), testClass.collect());
        verifyAll();
    }

    @Test
    public void update_empty_resource() {
        expect(accountProvider.getAccounts()).andReturn(ImmutableSet.of(account));
        expect(scrapeConfigProvider.getScrapeConfig()).andReturn(scrapeConfig);
        expect(scrapeConfig.getDiscoverResourceTypes()).andReturn(ImmutableSet.of("type1")).anyTimes();
        expect(scrapeConfig.getTagExportConfig()).andReturn(tagExportConfig).anyTimes();
        expect(awsClientProvider.getConfigClient("region", account)).andReturn(configClient).anyTimes();

        Capture<RateLimiter.AWSAPICall<ListDiscoveredResourcesResponse>> callbackCapture = Capture.newInstance();

        ListDiscoveredResourcesResponse response = ListDiscoveredResourcesResponse.builder().build();

        expect(configClient.listDiscoveredResources(ListDiscoveredResourcesRequest.builder()
                .includeDeletedResources(false)
                .resourceType("type1")
                .build())).andReturn(response);
        expect(resourceMapper.map(anyString())).andReturn(Optional.empty()).anyTimes();

        expect(rateLimiter.doWithRateLimit(eq("ConfigClient/listDiscoveredResources"),
                anyObject(SortedMap.class), capture(callbackCapture))).andReturn(response);

        replayAll();
        testClass.update();

        assertEquals(response, callbackCapture.getValue().makeCall());

        assertEquals(ImmutableList.of(), testClass.collect());
        verifyAll();
    }


    @Test
    void addBasicLabels_LoadBalancer() {
        TreeMap<String, String> labels = new TreeMap<>();

        expect(resource.getId()).andReturn(null).anyTimes();
        expect(resource.getAccount()).andReturn("account").anyTimes();
        expect(resource.getName()).andReturn("name").anyTimes();
        expect(resource.getType()).andReturn(LoadBalancer);
        expect(resource.getSubType()).andReturn("app");

        replayAll();
        testClass.addBasicLabels(labels, ResourceIdentifier.builder()
                .resourceId("id")
                .resourceName("name")
                .build(), "name", Optional.of(resource));
        assertEquals(ImmutableMap.of(
                "id", "id",
                "name", "name",
                "account_id", "account",
                "job", "name",
                "type", "app"), labels);
        verifyAll();
    }

    @Test
    void addBasicLabels_ECSService() {
        TreeMap<String, String> labels = new TreeMap<>();

        expect(resource.getId()).andReturn(null).anyTimes();
        expect(resource.getAccount()).andReturn("account").anyTimes();
        expect(resource.getName()).andReturn("name");
        expect(resource.getType()).andReturn(ECSService);
        expect(resource.getChildOf()).andReturn(resource);
        expect(resource.getName()).andReturn("cluster");

        replayAll();
        testClass.addBasicLabels(labels, ResourceIdentifier.builder()
                .resourceName("name")
                .build(), "name", Optional.of(resource));
        assertEquals(new ImmutableMap.Builder<String, String>()
                .put("name", "name")
                .put("cluster", "cluster")
                .put("account_id", "account")
                .put("job", "name").build(), labels);
        verifyAll();
    }

    @Test
    void addBasicLabels_ResourceAbsent() {
        TreeMap<String, String> labels = new TreeMap<>();

        replayAll();
        testClass.addBasicLabels(labels, ResourceIdentifier.builder()
                .build(), "idOrName", Optional.empty());
        assertEquals(ImmutableMap.of(
                "job", "idOrName"), labels);
        verifyAll();
    }

    @Test
    void addTagLabels_ResourceAbsent() {
        TreeMap<String, String> labels = new TreeMap<>();

        expect(resource.getName()).andReturn("name").anyTimes();
        expect(resource.getType()).andReturn(AutoScalingGroup).anyTimes();
        expect(resource.getSubType()).andReturn("k8s").anyTimes();
        resource.addTagLabels(labels, metricNameUtil);
        expectLastCall().times(2);

        replayAll();
        testClass.addTagLabels(ImmutableMap.of("name", resource), labels, ResourceIdentifier.builder()
                .resourceId("id")
                .resourceName("name")
                .build(), Optional.of(resource));
        assertEquals(ImmutableMap.of("subtype", "k8s"), labels);
        verifyAll();
    }
}
