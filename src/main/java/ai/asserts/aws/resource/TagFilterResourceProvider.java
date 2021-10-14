/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.resource;

import ai.asserts.aws.AWSClientProvider;
import ai.asserts.aws.cloudwatch.config.NamespaceConfig;
import ai.asserts.aws.cloudwatch.model.CWNamespace;
import ai.asserts.aws.cloudwatch.prometheus.GaugeExporter;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableMap;
import io.micrometer.core.instrument.util.StringUtils;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.resourcegroupstaggingapi.ResourceGroupsTaggingApiClient;
import software.amazon.awssdk.services.resourcegroupstaggingapi.model.GetResourcesRequest;
import software.amazon.awssdk.services.resourcegroupstaggingapi.model.GetResourcesResponse;
import software.amazon.awssdk.services.resourcegroupstaggingapi.model.TagFilter;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static ai.asserts.aws.MetricNameUtil.SCRAPE_LATENCY_METRIC;
import static ai.asserts.aws.MetricNameUtil.SCRAPE_NAMESPACE_LABEL;
import static ai.asserts.aws.MetricNameUtil.SCRAPE_OPERATION_LABEL;
import static ai.asserts.aws.MetricNameUtil.SCRAPE_REGION_LABEL;
import static java.lang.String.format;

@Component
@AllArgsConstructor
@Slf4j
public class TagFilterResourceProvider {
    private final AWSClientProvider awsClientProvider;
    private final ResourceMapper resourceMapper;
    private final GaugeExporter gaugeExporter;
    private final LoadingCache<Key, Set<Resource>> resourceCache = CacheBuilder.newBuilder()
            .expireAfterAccess(15, TimeUnit.MINUTES)
            .build(new CacheLoader<Key, Set<Resource>>() {
                @Override
                public Set<Resource> load(Key key) {
                    return getResourcesInternal(key);
                }
            });

    public Set<Resource> getFilteredResources(String region, NamespaceConfig namespaceConfig) {
        return resourceCache.getUnchecked(Key.builder()
                .region(region)
                .namespace(namespaceConfig)
                .build());
    }

    private Set<Resource> getResourcesInternal(Key key) {
        Set<Resource> resources = new HashSet<>();
        CWNamespace cwNamespace = CWNamespace.valueOf(key.getNamespace().getName());
        GetResourcesRequest.Builder builder = GetResourcesRequest.builder();
        ResourceGroupsTaggingApiClient resourceTagClient = awsClientProvider.getResourceTagClient(key.region);
        if (cwNamespace.getResourceTypes().size() > 0) {
            List<String> resourceTypeFilters = cwNamespace.getResourceTypes().stream()
                    .map(type -> format("%s:%s", cwNamespace.getServiceName(), type))
                    .collect(Collectors.toList());
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

        String nextToken = null;
        try {
            do {
                long timeTaken = System.currentTimeMillis();
                GetResourcesResponse response = resourceTagClient.getResources(builder
                        .paginationToken(nextToken)
                        .build());
                timeTaken = System.currentTimeMillis() - timeTaken;
                captureLatency(key.region, cwNamespace, timeTaken);

                if (response.hasResourceTagMappingList()) {
                    response.resourceTagMappingList().forEach(resourceTagMapping ->
                            resourceMapper.map(resourceTagMapping.resourceARN()).ifPresent(resource -> {
                                resource.setTags(resourceTagMapping.tags());
                                resources.add(resource);
                            }));
                }
                nextToken = response.paginationToken();
            } while (!StringUtils.isEmpty(nextToken));
        } catch (Exception e) {
            log.error("Failed to get resources using resource tag api", e);
        }
        log.info("Found {} resources", resources);
        return resources;
    }

    private void captureLatency(String region, CWNamespace cwNamespace, long timeTaken) {
        gaugeExporter.exportMetric(SCRAPE_LATENCY_METRIC, "scraper Instrumentation",
                ImmutableMap.of(
                        SCRAPE_REGION_LABEL, region,
                        SCRAPE_OPERATION_LABEL, "get_resources_with_tags",
                        SCRAPE_NAMESPACE_LABEL, cwNamespace.getNamespace()
                ), Instant.now(), timeTaken * 1.0D);
    }

    @EqualsAndHashCode
    @Builder
    @Getter
    public static class Key {
        private final String region;
        private final NamespaceConfig namespace;
    }
}
