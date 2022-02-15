/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.exporter;

import ai.asserts.aws.AWSClientProvider;
import ai.asserts.aws.MetricNameUtil;
import ai.asserts.aws.RateLimiter;
import ai.asserts.aws.cloudwatch.config.ScrapeConfig;
import ai.asserts.aws.cloudwatch.config.ScrapeConfigProvider;
import ai.asserts.aws.cloudwatch.config.TagExportConfig;
import ai.asserts.aws.resource.Resource;
import ai.asserts.aws.resource.ResourceMapper;
import ai.asserts.aws.resource.TagFilterResourceProvider;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import io.prometheus.client.Collector;
import io.prometheus.client.Collector.MetricFamilySamples.Sample;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import software.amazon.awssdk.services.config.ConfigClient;
import software.amazon.awssdk.services.config.model.ListDiscoveredResourcesRequest;
import software.amazon.awssdk.services.config.model.ListDiscoveredResourcesResponse;
import software.amazon.awssdk.services.config.model.ResourceIdentifier;
import software.amazon.awssdk.services.elasticloadbalancing.ElasticLoadBalancingClient;
import software.amazon.awssdk.services.elasticloadbalancing.model.DescribeTagsRequest;
import software.amazon.awssdk.services.elasticloadbalancing.model.DescribeTagsResponse;
import software.amazon.awssdk.services.resourcegroupstaggingapi.model.GetResourcesRequest;
import software.amazon.awssdk.services.resourcegroupstaggingapi.model.Tag;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static ai.asserts.aws.MetricNameUtil.SCRAPE_ACCOUNT_ID_LABEL;
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
    private final MetricNameUtil metricNameUtil;
    private final TagFilterResourceProvider tagFilterResourceProvider;
    private final AccountIDProvider accountIDProvider;
    private volatile List<MetricFamilySamples> metrics = new ArrayList<>();

    public ResourceExporter(ScrapeConfigProvider scrapeConfigProvider,
                            AWSClientProvider awsClientProvider,
                            RateLimiter rateLimiter,
                            MetricSampleBuilder sampleBuilder, ResourceMapper resourceMapper,
                            MetricNameUtil metricNameUtil,
                            TagFilterResourceProvider tagFilterResourceProvider,
                            AccountIDProvider accountIDProvider) {
        this.scrapeConfigProvider = scrapeConfigProvider;
        this.awsClientProvider = awsClientProvider;
        this.rateLimiter = rateLimiter;
        this.sampleBuilder = sampleBuilder;
        this.resourceMapper = resourceMapper;
        this.metricNameUtil = metricNameUtil;
        this.tagFilterResourceProvider = tagFilterResourceProvider;
        this.accountIDProvider = accountIDProvider;
    }

    @Override
    public void update() {
        List<Sample> samples = new ArrayList<>();
        ScrapeConfig scrapeConfig = scrapeConfigProvider.getScrapeConfig();
        Set<String> discoverResourceTypes = scrapeConfig.getDiscoverResourceTypes();
        if (discoverResourceTypes.size() > 0) {
            scrapeConfig.getRegions().forEach(region -> {
                log.info("Discovering resources in region {}", region);
                ConfigClient configClient = awsClientProvider.getConfigClient(region);
                discoverResourceTypes.forEach(resourceType ->
                        samples.addAll(getResources(scrapeConfig, region, configClient, resourceType)));
            });
            List<MetricFamilySamples> latest = new ArrayList<>();
            latest.add(sampleBuilder.buildFamily(samples));
            metrics = latest;
        }
    }

    private List<Sample> getResources(ScrapeConfig scrapeConfig, String region,
                                      ConfigClient configClient, String resourceType) {
        List<Sample> samples = new ArrayList<>();
        String[] nextToken = new String[]{null};
        try {
            do {
                ListDiscoveredResourcesResponse response = rateLimiter.doWithRateLimit(
                        "ConfigClient/listDiscoveredResources",
                        ImmutableSortedMap.of(
                                SCRAPE_REGION_LABEL, region,
                                SCRAPE_OPERATION_LABEL, "ConfigClient/listDiscoveredResources"
                        ),
                        () -> configClient.listDiscoveredResources(ListDiscoveredResourcesRequest.builder()
                                .includeDeletedResources(false)
                                .nextToken(nextToken[0])
                                .resourceType(resourceType)
                                .build()));

                Set<Resource> resources = new HashSet<>();
                Set<String> tagServiceNames = getTagServiceName(resourceType);
                Map<String, List<Tag>> tagsByName = new TreeMap<>();
                if (!CollectionUtils.isEmpty(tagServiceNames)) {
                    resources.addAll(tagFilterResourceProvider.getResourcesWithTag(region,
                            ImmutableSortedMap.of(
                                    SCRAPE_REGION_LABEL, region,
                                    SCRAPE_OPERATION_LABEL, "ConfigClient/listDiscoveredResources"
                            ), GetResourcesRequest.builder().resourceTypeFilters(tagServiceNames)));
                } else if (resourceType.equals("AWS::ElasticLoadBalancing::LoadBalancer")) {
                    Set<String> loadBalancerNames = response.resourceIdentifiers().stream()
                            .map(ResourceIdentifier::resourceName)
                            .collect(Collectors.toSet());
                    ElasticLoadBalancingClient elbClient = awsClientProvider.getELBClient(region);
                    DescribeTagsResponse describeTagsResponse = elbClient.describeTags(DescribeTagsRequest.builder()
                            .loadBalancerNames(loadBalancerNames)
                            .build());
                    describeTagsResponse.tagDescriptions().forEach(tagDescription ->
                            tagsByName.put(tagDescription.loadBalancerName(), tagDescription.tags().stream()
                                    .map(t -> Tag.builder()
                                            .key(t.key())
                                            .value(t.value())
                                            .build()).collect(Collectors.toList())));
                }

                Map<String, Resource> resourceByName = new TreeMap<>();

                resources.forEach(resource -> resourceByName.put(resource.getName(), resource));

                if (response.hasResourceIdentifiers()) {
                    SortedMap<String, String> labels = new TreeMap<>();
                    labels.put(SCRAPE_REGION_LABEL, region);
                    labels.put(SCRAPE_ACCOUNT_ID_LABEL, accountIDProvider.getAccountId());
                    response.resourceIdentifiers().forEach(rI -> {
                        String idOrName = Optional.ofNullable(rI.resourceId()).orElse(rI.resourceName());
                        log.debug("Discovered resource {}-{}", rI.resourceType().toString(), idOrName);
                        labels.put("aws_resource_type", rI.resourceType().toString());

                        Optional<Resource> arnResource = resourceMapper.map(idOrName);
                        addBasicLabels(labels, rI, idOrName, arnResource);
                        addTagLabels(scrapeConfig, tagsByName, resourceByName, labels, rI, arnResource);
                        Sample sample = sampleBuilder.buildSingleSample("aws_resource", labels, 1.0D);
                        samples.add(sample);
                    });
                }
                nextToken[0] = response.nextToken();
            } while (nextToken[0] != null);
        } catch (Exception e) {
            log.error("Failed to discover resources", e);
        }
        return samples;
    }

    private void addBasicLabels(SortedMap<String, String> labels, ResourceIdentifier rI, String idOrName,
                                Optional<Resource> arnResource) {
        if (arnResource.isPresent()) {
            arnResource.ifPresent(resource -> {
                labels.put("job", resource.getName());
                if (resource.getAccount() != null) {
                    labels.put(SCRAPE_ACCOUNT_ID_LABEL, resource.getAccount());
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
            labels.put("id", arnResource.isPresent() ? arnResource.get().getName() : rI.resourceId());
        }
        if (rI.resourceName() != null) {
            labels.put("name", rI.resourceName());
        }
    }

    private void addTagLabels(ScrapeConfig scrapeConfig,
                              Map<String, List<Tag>> tagsByName,
                              Map<String, Resource> resourceByName,
                              SortedMap<String, String> labels,
                              ResourceIdentifier rI, Optional<Resource> arnResource) {
        Stream.of(rI.resourceName(), rI.resourceId())
                .filter(Objects::nonNull)
                .findFirst()
                .ifPresent(key -> {
                    if (resourceByName.containsKey(key)) {
                        resourceByName.get(key).addTagLabels(labels, metricNameUtil);
                    } else if (tagsByName.containsKey(key)) {
                        TagExportConfig tagExportConfig = scrapeConfig.getTagExportConfig();
                        labels.putAll(tagExportConfig.tagLabels(tagsByName.get(key), metricNameUtil));
                    }
                });

        arnResource.ifPresent(res -> {
            if (resourceByName.containsKey(res.getName())) {
                resourceByName.get(res.getName()).addTagLabels(labels, metricNameUtil);
            }
        });
    }

    @Override
    public List<MetricFamilySamples> collect() {
        return metrics;
    }

    public Set<String> getTagServiceName(String configResourceType) {
        if (configResourceType.contains("SQS::Queue")) {
            return ImmutableSet.of("sqs:queue");
        } else if (configResourceType.contains("DynamoDB::Table")) {
            return ImmutableSet.of("dynamodb:table");
        } else if (configResourceType.contains("Lambda::Function")) {
            return ImmutableSet.of("lambda:function");
        } else if (configResourceType.contains("S3::Bucket")) {
            return ImmutableSet.of("s3:bucket");
        } else if (configResourceType.contains("SNS::Topic")) {
            return ImmutableSet.of("sns:topic");
        } else if (configResourceType.contains("Events::EventBus")) {
            return ImmutableSet.of("events:event-bus");
        } else if (configResourceType.contains("ECS::Cluster")) {
            return ImmutableSet.of("ecs:cluster");
        } else if (configResourceType.contains("ECS::Service")) {
            return ImmutableSet.of("ecs:service");
        } else if (configResourceType.contains("AutoScaling::AutoScalingGroup")) {
            return ImmutableSet.of("autoscaling:autoScalingGroup");
        } else if (configResourceType.contains("ElasticLoadBalancingV2::LoadBalancer")) {
            return ImmutableSet.of("elasticloadbalancing:loadbalancer/app", "elasticloadbalancing:loadbalancer/net");
        } else if (configResourceType.contains("RDS::DBCluster")) {
            return ImmutableSet.of("rds:cluster");
        } else if (configResourceType.contains("RDS::DBInstance")) {
            return ImmutableSet.of("rds:db");
        } else if (configResourceType.contains("ApiGateway::RestApi")) {
            return ImmutableSet.of("apigateway:restapi");
        } else if (configResourceType.contains("ApiGatewayV2::Api")) {
            return ImmutableSet.of("apigateway:api");
        } else if (configResourceType.contains("EC2::Instance")) {
            return ImmutableSet.of("apigateway:api");
        } else if (configResourceType.contains("Kinesis::Stream")) {
            return ImmutableSet.of("kinesis:stream");
        } else {
            return ImmutableSet.of();
        }
    }
}
