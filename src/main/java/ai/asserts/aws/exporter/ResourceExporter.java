/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.exporter;

import ai.asserts.aws.AWSClientProvider;
import ai.asserts.aws.RateLimiter;
import ai.asserts.aws.cloudwatch.config.ScrapeConfig;
import ai.asserts.aws.cloudwatch.config.ScrapeConfigProvider;
import ai.asserts.aws.resource.Resource;
import ai.asserts.aws.resource.ResourceMapper;
import com.google.common.collect.ImmutableSortedMap;
import io.prometheus.client.Collector;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.config.ConfigClient;
import software.amazon.awssdk.services.config.model.ListDiscoveredResourcesRequest;
import software.amazon.awssdk.services.config.model.ListDiscoveredResourcesResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

import static ai.asserts.aws.MetricNameUtil.SCRAPE_OPERATION_LABEL;
import static ai.asserts.aws.MetricNameUtil.SCRAPE_REGION_LABEL;

@Component
@Slf4j
public class ResourceExporter extends Collector implements MetricProvider {
    private final ScrapeConfigProvider scrapeConfigProvider;
    private final AWSClientProvider awsClientProvider;
    private final RateLimiter rateLimiter;
    private final MetricSampleBuilder sampleBuilder;
    private final ResourceMapper resourceMapper;
    private volatile List<MetricFamilySamples> metrics = new ArrayList<>();

    public ResourceExporter(ScrapeConfigProvider scrapeConfigProvider,
                            AWSClientProvider awsClientProvider,
                            RateLimiter rateLimiter,
                            MetricSampleBuilder sampleBuilder, ResourceMapper resourceMapper) {
        this.scrapeConfigProvider = scrapeConfigProvider;
        this.awsClientProvider = awsClientProvider;
        this.rateLimiter = rateLimiter;
        this.sampleBuilder = sampleBuilder;
        this.resourceMapper = resourceMapper;
    }

    @Override
    public void update() {
        List<MetricFamilySamples.Sample> samples = new ArrayList<>();
        ScrapeConfig scrapeConfig = scrapeConfigProvider.getScrapeConfig();
        Set<String> discoverResourceTypes = scrapeConfig.getDiscoverResourceTypes();
        if (discoverResourceTypes.size() > 0) {
            scrapeConfig.getRegions().forEach(region -> {
                log.info("Discovering resources in region {}", region);
                ConfigClient configClient = awsClientProvider.getConfigClient(region);
                discoverResourceTypes.forEach(resourceType ->
                        getResources(samples, region, configClient, resourceType));
            });
            List<MetricFamilySamples> latest = new ArrayList<>();
            latest.add(sampleBuilder.buildFamily(samples));
            metrics = latest;
        }
    }

    private void getResources(List<MetricFamilySamples.Sample> samples, String region, ConfigClient configClient,
                              String resourceType) {
        String[] nextToken = new String[]{null};
        try {
            do {
                ListDiscoveredResourcesResponse response = rateLimiter.doWithRateLimit("ConfigClient/listDiscoveredResources",
                        ImmutableSortedMap.of(
                                SCRAPE_REGION_LABEL, region,
                                SCRAPE_OPERATION_LABEL, "listDiscoveredResources"
                        ),
                        () -> configClient.listDiscoveredResources(ListDiscoveredResourcesRequest.builder()
                                .includeDeletedResources(false)
                                .nextToken(nextToken[0])
                                .resourceType(resourceType)
                                .build()));
                if (response.hasResourceIdentifiers()) {
                    SortedMap<String, String> labels = new TreeMap<>();
                    labels.put(SCRAPE_REGION_LABEL, region);
                    response.resourceIdentifiers().forEach(rI -> {
                        String idOrName = Optional.ofNullable(rI.resourceId()).orElse(rI.resourceName());
                        log.info("Discovered resource {}-{}", rI.resourceType().toString(), idOrName);
                        labels.put("aws_resource_type", rI.resourceType().toString());

                        Optional<Resource> arnResource = resourceMapper.map(idOrName);
                        if (arnResource.isPresent()) {
                            arnResource.ifPresent(resource -> {
                                labels.put("job", resource.getName());
                                if (resource.getAccount() != null) {
                                    labels.put("account_id", resource.getAccount());
                                }
                                switch (resource.getType()) {
                                    case LoadBalancer:
                                        labels.put("type", resource.getSubType());
                                        break;
                                    case ECSService:
                                        labels.put("cluster", resource.getChildOf().getName());
                                        break;
                                    default:
                                }
                            });
                        } else {
                            labels.put("job", idOrName);
                        }

                        if (rI.resourceId() != null) {
                            labels.put("id", rI.resourceId());
                        }
                        if (rI.resourceName() != null) {
                            labels.put("name", rI.resourceName());
                        }
                        samples.add(sampleBuilder.buildSingleSample("aws_resource", labels, 1.0D));
                    });
                }
                nextToken[0] = response.nextToken();
            } while (nextToken[0] != null);
        } catch (Exception e) {
            log.error("Failed to discover resources", e);
        }
    }

    @Override
    public List<MetricFamilySamples> collect() {
        return metrics;
    }

}
