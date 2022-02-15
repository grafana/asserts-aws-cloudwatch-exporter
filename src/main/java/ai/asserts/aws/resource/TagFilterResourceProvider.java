/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.resource;

import ai.asserts.aws.AWSClientProvider;
import ai.asserts.aws.RateLimiter;
import ai.asserts.aws.cloudwatch.config.NamespaceConfig;
import ai.asserts.aws.cloudwatch.config.ScrapeConfig;
import ai.asserts.aws.cloudwatch.config.ScrapeConfigProvider;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableSortedMap;
import io.micrometer.core.instrument.util.StringUtils;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.resourcegroupstaggingapi.ResourceGroupsTaggingApiClient;
import software.amazon.awssdk.services.resourcegroupstaggingapi.model.GetResourcesRequest;
import software.amazon.awssdk.services.resourcegroupstaggingapi.model.GetResourcesResponse;
import software.amazon.awssdk.services.resourcegroupstaggingapi.model.TagFilter;

import java.util.HashSet;
import java.util.Set;
import java.util.SortedMap;
import java.util.stream.Collectors;

import static ai.asserts.aws.MetricNameUtil.SCRAPE_NAMESPACE_LABEL;
import static ai.asserts.aws.MetricNameUtil.SCRAPE_OPERATION_LABEL;
import static ai.asserts.aws.MetricNameUtil.SCRAPE_REGION_LABEL;
import static java.lang.String.format;
import static java.util.concurrent.TimeUnit.MINUTES;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.joining;

@Component
@Slf4j
public class TagFilterResourceProvider {
    private final AWSClientProvider awsClientProvider;
    private final ResourceMapper resourceMapper;
    private final LoadingCache<Key, Set<Resource>> resourceCache;
    private final ScrapeConfigProvider scrapeConfigProvider;
    private final RateLimiter rateLimiter;

    public TagFilterResourceProvider(ScrapeConfigProvider scrapeConfigProvider, AWSClientProvider awsClientProvider,
                                     ResourceMapper resourceMapper,
                                     RateLimiter rateLimiter) {
        this.scrapeConfigProvider = scrapeConfigProvider;
        this.awsClientProvider = awsClientProvider;
        this.resourceMapper = resourceMapper;
        this.rateLimiter = rateLimiter;

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

    public Set<Resource> getFilteredResources(String region, NamespaceConfig namespaceConfig) {
        return resourceCache.getUnchecked(Key.builder()
                .region(region)
                .namespace(namespaceConfig)
                .build());
    }

    private Set<Resource> getResourcesInternal(Key key) {
        Set<Resource> resources = new HashSet<>();
        ScrapeConfig scrapeConfig = scrapeConfigProvider.getScrapeConfig();
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
            resources.addAll(getResourcesWithTag(key.region, labels, builder));
            log.info("Found {}", resources.stream()
                    .collect(groupingBy(Resource::getType))
                    .entrySet()
                    .stream()
                    .map(entry -> format("%d %s(s)", entry.getValue().size(), entry.getKey().name()))
                    .collect(joining(", ")));
        });
        return resources;
    }

    public Set<Resource> getResourcesWithTag(String region, SortedMap<String, String> labels,
                                             GetResourcesRequest.Builder builder) {
        ScrapeConfig scrapeConfig = scrapeConfigProvider.getScrapeConfig();
        Set<Resource> resources = new HashSet<>();
        String nextToken = null;
        try (ResourceGroupsTaggingApiClient client = awsClientProvider.getResourceTagClient(region)) {
            do {
                GetResourcesRequest req = builder
                        .paginationToken(nextToken)
                        .build();
                GetResourcesResponse response = rateLimiter.doWithRateLimit(
                        "ResourceGroupsTaggingApiClient/getResources", labels, () -> client.getResources(req));

                if (response.hasResourceTagMappingList()) {
                    response.resourceTagMappingList().forEach(resourceTagMapping ->
                            resourceMapper.map(resourceTagMapping.resourceARN()).ifPresent(resource -> {
                                resource.setTags(resourceTagMapping.tags().stream()
                                        .filter(scrapeConfig::shouldExportTag)
                                        .collect(Collectors.toList()));
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

    @EqualsAndHashCode
    @Builder
    @Getter
    public static class Key {
        private final String region;
        private final NamespaceConfig namespace;
    }
}
