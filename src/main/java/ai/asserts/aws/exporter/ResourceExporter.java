/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.exporter;

import ai.asserts.aws.AWSClientProvider;
import ai.asserts.aws.RateLimiter;
import ai.asserts.aws.cloudwatch.config.ScrapeConfig;
import ai.asserts.aws.cloudwatch.config.ScrapeConfigProvider;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableSortedMap;
import io.prometheus.client.Collector;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.config.ConfigClient;
import software.amazon.awssdk.services.config.model.ListDiscoveredResourcesRequest;
import software.amazon.awssdk.services.config.model.ListDiscoveredResourcesResponse;
import software.amazon.awssdk.services.config.model.ResourceType;

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
    private volatile List<MetricFamilySamples> metrics = new ArrayList<>();

    public ResourceExporter(ScrapeConfigProvider scrapeConfigProvider,
                            AWSClientProvider awsClientProvider,
                            RateLimiter rateLimiter,
                            MetricSampleBuilder sampleBuilder) {
        this.scrapeConfigProvider = scrapeConfigProvider;
        this.awsClientProvider = awsClientProvider;
        this.rateLimiter = rateLimiter;
        this.sampleBuilder = sampleBuilder;
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
                discoverResourceTypes.forEach(resourceType -> {
                    ListDiscoveredResourcesResponse response = rateLimiter.doWithRateLimit("ConfigClient/listDiscoveredResources",
                            ImmutableSortedMap.of(
                                    SCRAPE_REGION_LABEL, region,
                                    SCRAPE_OPERATION_LABEL, "listDiscoveredResources"
                            ),
                            () -> configClient.listDiscoveredResources(ListDiscoveredResourcesRequest.builder()
                                    .includeDeletedResources(false)
                                    .resourceType(resourceType)
                                    .build()));
                    if (response.hasResourceIdentifiers()) {
                        SortedMap<String, String> labels = new TreeMap<>();
                        labels.put(SCRAPE_REGION_LABEL, region);
                        response.resourceIdentifiers().forEach(rI -> {
                            String name = Optional.ofNullable(rI.resourceName()).orElse(rI.resourceId());
                            log.info("Discovered resource {}-{}", resourceTypeName(rI.resourceType()), name);
                            labels.put("aws_resource_type", resourceTypeName(rI.resourceType()));
                            labels.put("name", name);
                            labels.put("job", name);
                            samples.add(sampleBuilder.buildSingleSample("aws_resource", labels, 1.0D));
                        });
                    }
                });
            });
            List<MetricFamilySamples> latest = new ArrayList<>();
            latest.add(sampleBuilder.buildFamily(samples));
            metrics = latest;
        }
    }

    @Override
    public List<MetricFamilySamples> collect() {
        return metrics;
    }

    @VisibleForTesting
    String resourceTypeName(ResourceType resourceType) {
        switch (resourceType) {
            case AWS_DYNAMO_DB_TABLE:
                return "DynamoDBTable";
            case AWS_S3_BUCKET:
                return "S3Bucket";
            case AWS_ELASTIC_LOAD_BALANCING_LOAD_BALANCER:
                return "ELB";
            case AWS_ELASTIC_LOAD_BALANCING_V2_LOAD_BALANCER:
                return "ELB_V2";
            case AWS_SNS_TOPIC:
                return "SNSTopic";
            case AWS_SQS_QUEUE:
                return "SQSQueue";
            case AWS_API_GATEWAY_REST_API:
                return "APIGatewayRestAPI";
            case AWS_API_GATEWAY_V2_API:
                return "APIGatewayAPI";
            case AWS_AUTO_SCALING_AUTO_SCALING_GROUP:
                return "ASG";
            case AWS_KINESIS_STREAM:
                return "KinesisStream";
            case AWS_KINESIS_STREAM_CONSUMER:
                return "KinesisStreamConsumer";
            default:
                String[] split = resourceType.toString().split("::");
                return split[split.length - 1];
        }
    }
}
