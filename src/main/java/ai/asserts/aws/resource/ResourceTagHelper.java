/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.resource;

import ai.asserts.aws.AWSClientProvider;
import ai.asserts.aws.AccountProvider.AWSAccount;
import ai.asserts.aws.RateLimiter;
import ai.asserts.aws.ScrapeConfigProvider;
import ai.asserts.aws.TagUtil;
import ai.asserts.aws.config.NamespaceConfig;
import ai.asserts.aws.config.ScrapeConfig;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import io.micrometer.core.instrument.util.StringUtils;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import software.amazon.awssdk.services.autoscaling.AutoScalingClient;
import software.amazon.awssdk.services.elasticloadbalancing.ElasticLoadBalancingClient;
import software.amazon.awssdk.services.elasticloadbalancing.model.DescribeTagsRequest;
import software.amazon.awssdk.services.elasticloadbalancing.model.DescribeTagsResponse;
import software.amazon.awssdk.services.resourcegroupstaggingapi.ResourceGroupsTaggingApiClient;
import software.amazon.awssdk.services.resourcegroupstaggingapi.model.GetResourcesRequest;
import software.amazon.awssdk.services.resourcegroupstaggingapi.model.GetResourcesResponse;
import software.amazon.awssdk.services.resourcegroupstaggingapi.model.Tag;
import software.amazon.awssdk.services.resourcegroupstaggingapi.model.TagFilter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.stream.Collectors;

import static ai.asserts.aws.MetricNameUtil.SCRAPE_ACCOUNT_ID_LABEL;
import static ai.asserts.aws.MetricNameUtil.SCRAPE_NAMESPACE_LABEL;
import static ai.asserts.aws.MetricNameUtil.SCRAPE_OPERATION_LABEL;
import static ai.asserts.aws.MetricNameUtil.SCRAPE_REGION_LABEL;
import static ai.asserts.aws.resource.ResourceType.AutoScalingGroup;
import static ai.asserts.aws.resource.ResourceType.LoadBalancer;
import static java.lang.String.format;
import static java.util.concurrent.TimeUnit.MINUTES;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.joining;

@Component
@Slf4j
public class ResourceTagHelper {
    private final AWSClientProvider awsClientProvider;
    private final ResourceMapper resourceMapper;
    private final LoadingCache<Key, Set<Resource>> resourceCache;
    private final ScrapeConfigProvider scrapeConfigProvider;
    private final RateLimiter rateLimiter;
    private final TagUtil tagUtil;
    private final Map<String, Set<String>> configServiceToServiceNames = new TreeMap<>();

    public ResourceTagHelper(ScrapeConfigProvider scrapeConfigProvider,
                             AWSClientProvider awsClientProvider, ResourceMapper resourceMapper,
                             RateLimiter rateLimiter, TagUtil tagUtil) {
        this.scrapeConfigProvider = scrapeConfigProvider;
        this.awsClientProvider = awsClientProvider;
        this.resourceMapper = resourceMapper;
        this.rateLimiter = rateLimiter;
        this.tagUtil = tagUtil;

        configServiceToServiceNames.put("AWS::SQS::Queue", ImmutableSet.of("sqs:queue"));
        configServiceToServiceNames.put("AWS::DynamoDB::Table", ImmutableSet.of("dynamodb:table"));
        configServiceToServiceNames.put("AWS::Lambda::Function", ImmutableSet.of("lambda:function"));
        configServiceToServiceNames.put("AWS::S3::Bucket", ImmutableSet.of("s3:bucket"));
        configServiceToServiceNames.put("AWS::SNS::Topic", ImmutableSet.of("sns:topic"));
        configServiceToServiceNames.put("AWS::ECS::Cluster", ImmutableSet.of("ecs:cluster"));
        configServiceToServiceNames.put("AWS::ECS::Service", ImmutableSet.of("ecs:service"));
        configServiceToServiceNames.put("AWS::ElasticLoadBalancingV2::LoadBalancer",
                ImmutableSet.of("elasticloadbalancing:loadbalancer/app", "elasticloadbalancing:loadbalancer/net"));
        configServiceToServiceNames.put("AWS::ElasticLoadBalancing::LoadBalancer",
                ImmutableSet.of("elasticloadbalancing:loadbalancer"));
        configServiceToServiceNames.put("AWS::RDS::DBCluster", ImmutableSet.of("rds:cluster"));
        configServiceToServiceNames.put("AWS::RDS::DBInstance", ImmutableSet.of("rds:db"));
        configServiceToServiceNames.put("AWS::ApiGateway::RestApi", ImmutableSet.of("apigateway:restapi"));
        configServiceToServiceNames.put("AWS::ApiGatewayV2::Api", ImmutableSet.of("apigateway:api"));
        configServiceToServiceNames.put("AWS::EC2::Instance", ImmutableSet.of("ec2:instance"));
        configServiceToServiceNames.put("AWS::Kinesis::Stream", ImmutableSet.of("kinesis:stream"));

        ScrapeConfig scrapeConfig = scrapeConfigProvider.getScrapeConfig();
        resourceCache = CacheBuilder.newBuilder()
                .expireAfterAccess(scrapeConfig.getGetResourcesResultCacheTTLMinutes(), MINUTES)
                .build(new CacheLoader<Key, Set<Resource>>() {
                    @Override
                    public Set<Resource> load(@NonNull Key key) {
                        return getResourcesInternal(key);
                    }
                });


    }

    public Set<Resource> getFilteredResources(AWSAccount accountRegion, String region, NamespaceConfig namespaceConfig) {
        return resourceCache.getUnchecked(Key.builder()
                .accountRegion(accountRegion)
                .region(region)
                .namespace(namespaceConfig)
                .build());
    }

    private Set<Resource> getResourcesInternal(Key key) {
        Set<Resource> resources = new HashSet<>();
        scrapeConfigProvider.getStandardNamespace(key.namespace.getName()).ifPresent(cwNamespace -> {
            GetResourcesRequest.Builder builder = GetResourcesRequest.builder();
            if (cwNamespace.getResourceTypes().size() > 0) {
                Set<String> resourceTypeFilters = cwNamespace.getResourceTypes().stream()
                        .map(type -> format("%s:%s", cwNamespace.getServiceName(), type))
                        .collect(Collectors.toSet());
                builder = builder.resourceTypeFilters(resourceTypeFilters);
                log.info("Applying resource type filters {}", resourceTypeFilters);
            } else {
                builder = builder.resourceTypeFilters(cwNamespace.getServiceName());
                log.info("Applying resource type filters {}", cwNamespace.getServiceName());
            }

            if (key.getNamespace().hasTagFilters()) {
                builder = builder.tagFilters(key.getNamespace().getTagFilters().entrySet().stream()
                        .map(entry -> TagFilter.builder()
                                .key(entry.getKey())
                                .values(entry.getValue())
                                .build())
                        .collect(Collectors.toSet()));
            }

            ImmutableSortedMap<String, String> labels = ImmutableSortedMap.of(
                    SCRAPE_REGION_LABEL, key.region,
                    SCRAPE_NAMESPACE_LABEL, key.namespace.getName(),
                    SCRAPE_OPERATION_LABEL, "getResources"
            );
            resources.addAll(getResourcesWithTag(key.accountRegion, key.region, labels, builder));
            log.info("Found {}", resources.stream()
                    .collect(groupingBy(Resource::getType))
                    .entrySet()
                    .stream()
                    .map(entry -> format("%d %s(s)", entry.getValue().size(), entry.getKey().name()))
                    .collect(joining(", ")));
        });
        return resources;
    }

    public Set<Resource> getResourcesWithTag(AWSAccount accountRegion, String region, SortedMap<String, String> labels,
                                             GetResourcesRequest.Builder builder) {
        ScrapeConfig scrapeConfig = scrapeConfigProvider.getScrapeConfig();
        Set<Resource> resources = new HashSet<>();
        String nextToken = null;
        String assumeRole = accountRegion.getAssumeRole();
        try (ResourceGroupsTaggingApiClient client = awsClientProvider.getResourceTagClient(region, accountRegion)) {
            do {
                GetResourcesRequest req = builder
                        .paginationToken(nextToken)
                        .build();
                SortedMap<String, String> telemetryLabels = new TreeMap<>(labels);
                telemetryLabels.put(SCRAPE_ACCOUNT_ID_LABEL, accountRegion.getAccountId());
                telemetryLabels.put(SCRAPE_OPERATION_LABEL, "ResourceGroupsTaggingApiClient/getResources");
                GetResourcesResponse response = rateLimiter.doWithRateLimit(
                        "ResourceGroupsTaggingApiClient/getResources", telemetryLabels, () -> client.getResources(req));

                if (response.hasResourceTagMappingList()) {
                    response.resourceTagMappingList().forEach(resourceTagMapping ->
                            resourceMapper.map(resourceTagMapping.resourceARN()).ifPresent(resource -> {
                                resource.setTags(resourceTagMapping.tags().stream()
                                        .filter(t -> scrapeConfig.shouldExportTag(t.key(), t.value()))
                                        .collect(Collectors.toList()));
                                tagUtil.setEnvTag(resource);
                                resources.add(resource);
                            }));
                }
                nextToken = response.paginationToken();
            } while (!StringUtils.isEmpty(nextToken));
        } catch (Exception e) {
            log.error("Failed to get resources using resource tag api", e);
        }
        return resources;
    }

    public Map<String, Resource> getResourcesWithTag(AWSAccount accountRegion,
                                                     String region, String resourceType, List<String> resourceNames) {
        Map<String, Resource> resourceByName = new TreeMap<>();
        if (CollectionUtils.isEmpty(resourceNames) || resourceNames.stream().noneMatch(StringUtils::isNotEmpty)) {
            return resourceByName;
        }
        resourceNames = resourceNames.stream().filter(StringUtils::isNotEmpty).collect(Collectors.toList());
        Set<Resource> resources = new HashSet<>();
        Set<String> tagServiceNames = configServiceToServiceNames.getOrDefault(resourceType, Collections.emptySet());
        Map<String, List<Tag>> classLBTagsByName = new TreeMap<>();
        if (!CollectionUtils.isEmpty(tagServiceNames)) {
            resources.addAll(getResourcesWithTag(accountRegion, region,
                    ImmutableSortedMap.of(
                            SCRAPE_ACCOUNT_ID_LABEL, accountRegion.getAccountId(),
                            SCRAPE_REGION_LABEL, region,
                            SCRAPE_OPERATION_LABEL, "ConfigClient/listDiscoveredResources"
                    ), GetResourcesRequest.builder().resourceTypeFilters(tagServiceNames)));
        }
        if (resourceType.equals("AWS::ElasticLoadBalancing::LoadBalancer")) {
            ScrapeConfig scrapeConfig = scrapeConfigProvider.getScrapeConfig();
            String assumeRole = accountRegion.getAssumeRole();
            try (ElasticLoadBalancingClient elbClient = awsClientProvider.getELBClient(region, accountRegion)) {
                DescribeTagsResponse describeTagsResponse = elbClient.describeTags(DescribeTagsRequest.builder()
                        .loadBalancerNames(resourceNames)
                        .build());
                describeTagsResponse.tagDescriptions().forEach(tagDescription ->
                        classLBTagsByName.put(tagDescription.loadBalancerName(), tagDescription.tags().stream()
                                .map(t -> Tag.builder()
                                        .key(t.key())
                                        .value(t.value())
                                        .build())
                                .filter(t -> scrapeConfig.shouldExportTag(t.key(), t.value()))
                                .collect(Collectors.toList())));
            }
        } else if (resourceType.equals("AWS::AutoScaling::AutoScalingGroup")) {
            try (AutoScalingClient asgClient = awsClientProvider.getAutoScalingClient(region, accountRegion)) {
                software.amazon.awssdk.services.autoscaling.model.DescribeTagsResponse describeTagsResponse = asgClient.describeTags(software.amazon.awssdk.services.autoscaling.model.DescribeTagsRequest.builder()
                        .build());
                describeTagsResponse.tags()
                        .forEach(tagDescription -> {
                            if (tagDescription.key().contains("k8s") || tagDescription.key().contains("kubernetes")) {
                                resourceByName.computeIfAbsent(tagDescription.resourceId(), k -> Resource.builder()
                                        .name(tagDescription.resourceId())
                                        .type(AutoScalingGroup)
                                        .subType("k8s")
                                        .region(region)
                                        .account(accountRegion.getAccountId())
                                        .build());
                            }
                        });
            }
        }

        resources.forEach(resource -> {
            List<Tag> allTags = new ArrayList<>();
            if (classLBTagsByName.containsKey(resource.getName())) {
                allTags.addAll(classLBTagsByName.get(resource.getName()));
            }
            if (!CollectionUtils.isEmpty(resource.getTags())) {
                allTags.addAll(resource.getTags());
            }
            resource.setTags(allTags);
            resourceByName.put(resource.getName(), resource);
        });

        classLBTagsByName.forEach((name, tags) -> {
            if (!resourceByName.containsKey(name)) {
                resourceByName.put(name, Resource.builder()
                        .type(LoadBalancer)
                        .name(name)
                        .account(accountRegion.getAccountId())
                        .region(region)
                        .tags(tags)
                        .build());
            }
        });

        return resourceByName;
    }

    @EqualsAndHashCode
    @Builder
    @Getter
    public static class Key {
        private final AWSAccount accountRegion;
        private final String region;
        private final NamespaceConfig namespace;
    }
}
