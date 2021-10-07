/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.resource;

import ai.asserts.aws.AWSClientProvider;
import ai.asserts.aws.cloudwatch.config.NamespaceConfig;
import ai.asserts.aws.cloudwatch.model.CWNamespace;
import ai.asserts.aws.cloudwatch.prometheus.GaugeExporter;
import com.google.common.collect.ImmutableMap;
import io.micrometer.core.instrument.util.StringUtils;
import lombok.AllArgsConstructor;
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
import java.util.stream.Collectors;

import static ai.asserts.aws.MetricNameUtil.SELF_LATENCY_METRIC;
import static ai.asserts.aws.MetricNameUtil.SELF_NAMESPACE_LABEL;
import static ai.asserts.aws.MetricNameUtil.SELF_OPERATION_LABEL;
import static ai.asserts.aws.MetricNameUtil.SELF_REGION_LABEL;
import static java.lang.String.format;

@Component
@AllArgsConstructor
@Slf4j
public class TagFilterResourceProvider {
    private final AWSClientProvider awsClientProvider;
    private final ResourceMapper resourceMapper;
    private final GaugeExporter gaugeExporter;

    public Set<Resource> getFilteredResources(String region, NamespaceConfig namespaceConfig) {
        Set<Resource> resources = new HashSet<>();

        ResourceGroupsTaggingApiClient resourceTagClient = awsClientProvider.getResourceTagClient(region);
        GetResourcesRequest.Builder builder = GetResourcesRequest.builder();
        CWNamespace cwNamespace = CWNamespace.valueOf(namespaceConfig.getName());
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

        if (namespaceConfig.hasTagFilters()) {
            builder = builder.tagFilters(namespaceConfig.getTagFilters().entrySet().stream()
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
                captureLatency(region, cwNamespace, timeTaken);

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
        gaugeExporter.exportMetric(SELF_LATENCY_METRIC, "scraper Instrumentation",
                ImmutableMap.of(
                        SELF_REGION_LABEL, region,
                        SELF_OPERATION_LABEL, "get_resources_with_tags",
                        SELF_NAMESPACE_LABEL, cwNamespace.getNamespace()
                ), Instant.now(), timeTaken * 1.0D);
    }
}
