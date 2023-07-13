/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.resource;

import ai.asserts.aws.AWSClientProvider;
import ai.asserts.aws.AWSApiCallRateLimiter;
import ai.asserts.aws.ScrapeConfigProvider;
import ai.asserts.aws.account.AWSAccount;
import ai.asserts.aws.account.AccountTenantMapper;
import ai.asserts.aws.config.NamespaceConfig;
import ai.asserts.aws.config.ScrapeConfig;
import ai.asserts.aws.exporter.BasicMetricCollector;
import ai.asserts.aws.model.CWNamespace;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import org.easymock.EasyMockSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.elasticloadbalancing.ElasticLoadBalancingClient;
import software.amazon.awssdk.services.elasticloadbalancing.model.DescribeTagsRequest;
import software.amazon.awssdk.services.elasticloadbalancing.model.DescribeTagsResponse;
import software.amazon.awssdk.services.elasticloadbalancing.model.TagDescription;
import software.amazon.awssdk.services.resourcegroupstaggingapi.ResourceGroupsTaggingApiClient;
import software.amazon.awssdk.services.resourcegroupstaggingapi.model.GetResourcesRequest;
import software.amazon.awssdk.services.resourcegroupstaggingapi.model.GetResourcesResponse;
import software.amazon.awssdk.services.resourcegroupstaggingapi.model.ResourceTagMapping;
import software.amazon.awssdk.services.resourcegroupstaggingapi.model.Tag;
import software.amazon.awssdk.services.resourcegroupstaggingapi.model.TagFilter;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;

import static ai.asserts.aws.model.CWNamespace.kafka;
import static ai.asserts.aws.model.CWNamespace.lambda;
import static org.easymock.EasyMock.anyLong;
import static org.easymock.EasyMock.anyObject;
import static org.easymock.EasyMock.expect;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class ResourceTagHelperTest extends EasyMockSupport {
    private ScrapeConfigProvider scrapeConfigProvider;
    private ScrapeConfig scrapeConfig;
    private AWSClientProvider awsClientProvider;
    private ResourceGroupsTaggingApiClient apiClient;
    private ElasticLoadBalancingClient elbClient;
    private ResourceMapper resourceMapper;
    private Resource resource;
    private NamespaceConfig namespaceConfig;
    private BasicMetricCollector metricCollector;
    private AWSAccount accountRegion;
    private AWSApiCallRateLimiter rateLimiter;
    private AccountTenantMapper accountTenantMapper;
    private ResourceTagHelper testClass;

    @BeforeEach
    public void setup() {
        accountRegion = new AWSAccount("tenant", "account", "", "", "role",
                ImmutableSet.of("role1"));
        scrapeConfigProvider = mock(ScrapeConfigProvider.class);
        scrapeConfig = mock(ScrapeConfig.class);
        awsClientProvider = mock(AWSClientProvider.class);
        resourceMapper = mock(ResourceMapper.class);
        namespaceConfig = mock(NamespaceConfig.class);
        apiClient = mock(ResourceGroupsTaggingApiClient.class);
        resource = mock(Resource.class);
        metricCollector = mock(BasicMetricCollector.class);
        elbClient = mock(ElasticLoadBalancingClient.class);
        accountTenantMapper = mock(AccountTenantMapper.class);
        rateLimiter = new AWSApiCallRateLimiter(metricCollector, (account) -> "tenant");
        scrapeConfig = mock(ScrapeConfig.class);
        testClass = new ResourceTagHelper(scrapeConfigProvider, awsClientProvider, resourceMapper, rateLimiter,
                accountTenantMapper);
    }

    @Test
    void filterResources() {
        expect(accountTenantMapper.getTenantName("account")).andReturn("tenant").anyTimes();
        expect(scrapeConfigProvider.getScrapeConfig("tenant")).andReturn(scrapeConfig).anyTimes();
        expect(namespaceConfig.getName()).andReturn(lambda.name()).anyTimes();
        expect(scrapeConfigProvider.getStandardNamespace("lambda")).andReturn(Optional.of(lambda));
        expect(namespaceConfig.hasTagFilters()).andReturn(true);
        expect(namespaceConfig.getTagFilters()).andReturn(ImmutableMap.of(
                "tag", ImmutableSortedSet.of("value1", "value2")
        ));
        expect(awsClientProvider.getResourceTagClient("region", accountRegion)).andReturn(apiClient);

        Tag tag1 = Tag.builder()
                .key("tag").value("value1")
                .build();
        expect(scrapeConfig.shouldExportTag("tag", "value1")).andReturn(true);
        Tag tag2 = Tag.builder()
                .key("tag").value("value2")
                .build();
        expect(scrapeConfig.shouldExportTag("tag", "value2")).andReturn(true);

        expect(apiClient.getResources(GetResourcesRequest.builder()
                .resourceTypeFilters(ImmutableList.of("lambda:function"))
                .tagFilters(ImmutableList.of(TagFilter.builder()
                        .key("tag").values(ImmutableSortedSet.of("value1", "value2"))
                        .build()))
                .build())).andReturn(
                GetResourcesResponse.builder()
                        .resourceTagMappingList(ImmutableList.of(ResourceTagMapping.builder()
                                .tags(tag1)
                                .resourceARN("arn1")
                                .build()))
                        .paginationToken("token1")
                        .build()
        );
        metricCollector.recordLatency(anyObject(), anyObject(), anyLong());

        expect(resourceMapper.map("arn1")).andReturn(Optional.of(resource));
        resource.setTags(ImmutableList.of(tag1));

        expect(apiClient.getResources(GetResourcesRequest.builder()
                .paginationToken("token1")
                .resourceTypeFilters(ImmutableList.of("lambda:function"))
                .tagFilters(ImmutableList.of(TagFilter.builder()
                        .key("tag").values(ImmutableSortedSet.of("value1", "value2"))
                        .build()))
                .build())).andReturn(
                GetResourcesResponse.builder()
                        .resourceTagMappingList(ImmutableList.of(ResourceTagMapping.builder()
                                .resourceARN("arn2")
                                .tags(tag2)
                                .build()))
                        .paginationToken(null)
                        .build()
        );
        metricCollector.recordLatency(anyObject(), anyObject(), anyLong());

        expect(resourceMapper.map("arn2")).andReturn(Optional.of(resource));
        resource.setTags(ImmutableList.of(tag2));

        expect(resource.getType()).andReturn(ResourceType.LambdaFunction).anyTimes();
        replayAll();
        assertEquals(ImmutableSet.of(resource),
                testClass.getFilteredResources(accountRegion, "region", namespaceConfig));
        verifyAll();
    }

    @Test
    void filterResources_noResourceTypes() {
        expect(accountTenantMapper.getTenantName("account")).andReturn("tenant").anyTimes();
        expect(scrapeConfigProvider.getScrapeConfig("tenant")).andReturn(scrapeConfig).anyTimes();
        expect(namespaceConfig.getName()).andReturn(CWNamespace.kafka.name()).anyTimes();
        expect(scrapeConfigProvider.getStandardNamespace("kafka")).andReturn(Optional.of(kafka));
        expect(namespaceConfig.hasTagFilters()).andReturn(false);
        expect(awsClientProvider.getResourceTagClient("region", accountRegion)).andReturn(apiClient);

        Tag tag1 = Tag.builder()
                .key("tag").value("value1")
                .build();
        expect(scrapeConfig.shouldExportTag("tag", "value1")).andReturn(true);

        Tag tag2 = Tag.builder()
                .key("tag").value("value2")
                .build();
        expect(scrapeConfig.shouldExportTag("tag", "value2")).andReturn(true);

        expect(apiClient.getResources(GetResourcesRequest.builder()
                .resourceTypeFilters(ImmutableList.of("kafka"))
                .build())).andReturn(
                GetResourcesResponse.builder()
                        .resourceTagMappingList(ImmutableList.of(ResourceTagMapping.builder()
                                .tags(tag1)
                                .resourceARN("arn1")
                                .build()))
                        .paginationToken("token1")
                        .build()
        );
        metricCollector.recordLatency(anyObject(), anyObject(), anyLong());
        expect(resourceMapper.map("arn1")).andReturn(Optional.of(resource));
        resource.setTags(ImmutableList.of(tag1));

        expect(apiClient.getResources(GetResourcesRequest.builder()
                .paginationToken("token1")
                .resourceTypeFilters(ImmutableList.of("kafka"))
                .build())).andReturn(
                GetResourcesResponse.builder()
                        .resourceTagMappingList(ImmutableList.of(ResourceTagMapping.builder()
                                .resourceARN("arn2")
                                .tags(tag2)
                                .build()))
                        .paginationToken(null)
                        .build()
        );
        metricCollector.recordLatency(anyObject(), anyObject(), anyLong());
        expect(resourceMapper.map("arn2")).andReturn(Optional.of(resource));
        resource.setTags(ImmutableList.of(tag2));

        expect(resource.getType()).andReturn(ResourceType.LambdaFunction).anyTimes();
        replayAll();
        assertEquals(ImmutableSet.of(resource),
                testClass.getFilteredResources(accountRegion, "region", namespaceConfig));
        verifyAll();
    }

    @Test
    void filterResources_customNamespace() {
        expect(accountTenantMapper.getTenantName("account")).andReturn("tenant").anyTimes();
        expect(scrapeConfigProvider.getScrapeConfig("tenant")).andReturn(scrapeConfig).anyTimes();
        expect(namespaceConfig.getName()).andReturn("lambda");
        expect(scrapeConfigProvider.getStandardNamespace("lambda")).andReturn(Optional.empty());
        replayAll();
        assertEquals(ImmutableSet.of(), testClass.getFilteredResources(accountRegion, "region", namespaceConfig));
        verifyAll();
    }

    @Test
    void getResourcesWithTag() {
        expect(accountTenantMapper.getTenantName("account")).andReturn("tenant");
        expect(scrapeConfigProvider.getScrapeConfig("tenant")).andReturn(scrapeConfig);
        expect(resource.getName()).andReturn("resourceName").times(2);
        ImmutableList<Tag> tags = ImmutableList.of(Tag.builder()
                .key("name").value("value")
                .build());
        expect(resource.getTags()).andReturn(tags).anyTimes();
        resource.setTags(tags);
        replayAll();

        ImmutableList<String> resourceName = ImmutableList.of("resourceName");
        testClass = new ResourceTagHelper(scrapeConfigProvider, awsClientProvider, resourceMapper, rateLimiter,
                accountTenantMapper) {
            @Override
            public Set<Resource> getResourcesWithTag(AWSAccount _passedValue, String region, SortedMap<String,
                    String> labels,
                                                     GetResourcesRequest.Builder builder) {
                assertEquals(accountRegion, _passedValue);
                assertEquals("region", region);
                return ImmutableSet.of(resource);
            }
        };

        Map<String, Resource> map = testClass.getResourcesWithTag(
                accountRegion,
                "region", "AWS::S3::Bucket", resourceName);
        assertEquals(ImmutableMap.of("resourceName", resource), map);
        verifyAll();
    }

    @Test
    void getResourcesWithTag_LoadBalancer() {
        expect(accountTenantMapper.getTenantName("account")).andReturn("tenant");
        expect(scrapeConfigProvider.getScrapeConfig("tenant")).andReturn(scrapeConfig);
        expect(resource.getName()).andReturn("resourceName").anyTimes();

        Tag resourceTag = Tag.builder()
                .key("name").value("value")
                .build();
        ImmutableList<Tag> tags = ImmutableList.of(resourceTag);

        software.amazon.awssdk.services.elasticloadbalancing.model.Tag lbTag =
                software.amazon.awssdk.services.elasticloadbalancing.model.Tag.builder()
                        .key("tag2").value("value2")
                        .build();

        Tag lbTagConverted = Tag.builder()
                .key("tag2").value("value2")
                .build();

        expect(resource.getTags()).andReturn(tags).anyTimes();

        expect(awsClientProvider.getELBClient("region", accountRegion)).andReturn(elbClient);
        expect(elbClient.describeTags(DescribeTagsRequest.builder()
                .loadBalancerNames("resourceName")
                .build())).andReturn(DescribeTagsResponse.builder()
                .tagDescriptions(TagDescription.builder()
                        .loadBalancerName("resourceName")
                        .tags(lbTag)
                        .build())
                .build());

        resource.setTags(ImmutableList.of(lbTagConverted, resourceTag));

        expect(scrapeConfigProvider.getScrapeConfig("tenant")).andReturn(scrapeConfig).anyTimes();
        expect(scrapeConfig.shouldExportTag("tag2", "value2")).andReturn(true);
        replayAll();

        ImmutableList<String> resourceName = ImmutableList.of("resourceName");
        testClass = new ResourceTagHelper(scrapeConfigProvider, awsClientProvider, resourceMapper, rateLimiter,
                accountTenantMapper) {
            @Override
            public Set<Resource> getResourcesWithTag(AWSAccount _passed, String region,
                                                     SortedMap<String, String> labels,
                                                     GetResourcesRequest.Builder builder) {
                assertEquals(accountRegion, _passed);
                assertEquals("region", region);
                return ImmutableSet.of(resource);
            }
        };

        Map<String, Resource> map = testClass.getResourcesWithTag(accountRegion, "region",
                "AWS::ElasticLoadBalancing::LoadBalancer",
                resourceName);
        assertEquals(ImmutableMap.of("resourceName", resource), map);
        verifyAll();
    }
}
