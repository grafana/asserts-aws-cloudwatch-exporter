/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.exporter;

import ai.asserts.aws.AWSClientProvider;
import ai.asserts.aws.AccountProvider;
import ai.asserts.aws.RateLimiter;
import ai.asserts.aws.TagUtil;
import ai.asserts.aws.resource.Resource;
import ai.asserts.aws.resource.ResourceRelation;
import io.prometheus.client.Collector;
import io.prometheus.client.Collector.MetricFamilySamples.Sample;
import io.prometheus.client.CollectorRegistry;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.DescribeTagsRequest;
import software.amazon.awssdk.services.ec2.model.DescribeTagsResponse;
import software.amazon.awssdk.services.ec2.model.DescribeVolumesRequest;
import software.amazon.awssdk.services.ec2.model.DescribeVolumesResponse;
import software.amazon.awssdk.services.resourcegroupstaggingapi.model.Tag;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static ai.asserts.aws.MetricNameUtil.SCRAPE_ACCOUNT_ID_LABEL;
import static ai.asserts.aws.MetricNameUtil.SCRAPE_OPERATION_LABEL;
import static ai.asserts.aws.MetricNameUtil.SCRAPE_REGION_LABEL;
import static ai.asserts.aws.resource.ResourceType.EBSVolume;
import static ai.asserts.aws.resource.ResourceType.EC2Instance;
import static software.amazon.awssdk.services.ec2.model.ResourceType.INSTANCE;

@Slf4j
@Component
public class EC2ToEBSVolumeExporter extends Collector implements MetricProvider, InitializingBean {
    private final AccountProvider accountProvider;
    private final AWSClientProvider awsClientProvider;
    private final MetricSampleBuilder metricSampleBuilder;
    private final CollectorRegistry collectorRegistry;
    private final RateLimiter rateLimiter;

    private final TagUtil tagUtil;


    @Getter
    private volatile Set<ResourceRelation> attachedVolumes = new HashSet<>();
    private volatile List<MetricFamilySamples> resourceMetrics = new ArrayList<>();

    public EC2ToEBSVolumeExporter(AccountProvider accountProvider, AWSClientProvider awsClientProvider,
                                  MetricSampleBuilder metricSampleBuilder, CollectorRegistry collectorRegistry,
                                  RateLimiter rateLimiter, TagUtil tagUtil) {
        this.accountProvider = accountProvider;
        this.awsClientProvider = awsClientProvider;
        this.metricSampleBuilder = metricSampleBuilder;
        this.collectorRegistry = collectorRegistry;
        this.rateLimiter = rateLimiter;
        this.tagUtil = tagUtil;
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        collectorRegistry.register(this);
    }

    @Override
    public List<MetricFamilySamples> collect() {
        return resourceMetrics;
    }

    public void update() {
        log.info("Export EC2 Instances and EBS Volumes attached to EC2 Instances");
        List<Sample> samples = new ArrayList<>();
        List<MetricFamilySamples> newMetrics = new ArrayList<>();
        Set<ResourceRelation> newAttachedVolumes = new HashSet<>();
        Set<Resource> ec2Instances = new HashSet<>();
        accountProvider.getAccounts().forEach(awsAccount -> awsAccount.getRegions().forEach(region -> {
            Set<Resource> instancesInRegion = new HashSet<>();
            try {
                Ec2Client ec2Client = awsClientProvider.getEc2Client(region, awsAccount);
                SortedMap<String, String> telemetryLabels = new TreeMap<>();
                String api = "Ec2Client/describeVolumes";
                String accountId = awsAccount.getAccountId();

                telemetryLabels.put(SCRAPE_OPERATION_LABEL, api);
                telemetryLabels.put(SCRAPE_REGION_LABEL, region);
                telemetryLabels.put(SCRAPE_ACCOUNT_ID_LABEL, accountId);

                AtomicReference<String> nextToken = new AtomicReference<>();
                do {
                    DescribeVolumesResponse resp = rateLimiter.doWithRateLimit(api, telemetryLabels,
                            () -> ec2Client.describeVolumes(DescribeVolumesRequest.builder()
                                    .nextToken(nextToken.get())
                                    .build()));
                    if (!CollectionUtils.isEmpty(resp.volumes())) {
                        newAttachedVolumes.addAll(resp.volumes().stream()
                                .flatMap(volume -> volume.attachments().stream())
                                .map(volumeAttachment -> {
                                    Resource ec2Instance = Resource.builder()
                                            .account(accountId)
                                            .region(region)
                                            .type(EC2Instance)
                                            .name(volumeAttachment.instanceId())
                                            .build();
                                    instancesInRegion.add(ec2Instance);
                                    return ResourceRelation.builder()
                                            .from(Resource.builder()
                                                    .account(accountId)
                                                    .region(region)
                                                    .type(EBSVolume)
                                                    .name(volumeAttachment.volumeId())
                                                    .build())
                                            .to(ec2Instance)
                                            .name("ATTACHED_TO")
                                            .build();
                                })
                                .collect(Collectors.toSet()));
                    }
                    nextToken.set(resp.nextToken());
                } while (nextToken.get() != null);

                nextToken.set(null);
                Map<String, List<Tag>> byName = new TreeMap<>();
                do {
                    api = "Ec2Client/describeTags";
                    telemetryLabels.put(SCRAPE_OPERATION_LABEL, api);
                    DescribeTagsResponse response = rateLimiter.doWithRateLimit(api, telemetryLabels,
                            () -> ec2Client.describeTags(DescribeTagsRequest.builder()
                                    .nextToken(nextToken.get())
                                    .build()));
                    if (response.hasTags()) {
                        response.tags().stream()
                                .filter(tagDescription -> INSTANCE.name().equals(tagDescription.resourceType().name()))
                                .forEach(tagDescription -> byName.computeIfAbsent(tagDescription.resourceId(),
                                                k -> new ArrayList<>())
                                        .add(Tag.builder()
                                                .key(tagDescription.key())
                                                .value(tagDescription.value())
                                                .build()));
                    }
                    nextToken.set(response.nextToken());
                } while (nextToken.get() != null);

                instancesInRegion.forEach(instance -> {
                    if (byName.containsKey(instance.getName())) {
                        instance.setTags(byName.get(instance.getName()));
                    }
                });

                ec2Instances.addAll(instancesInRegion);
            } catch (Exception e) {
                log.error("Failed to fetch ec2 ebs volumes relation", e);
            }
        }));

        ec2Instances.forEach(instance -> {
            Map<String, String> labels = new TreeMap<>();
            labels.put(SCRAPE_ACCOUNT_ID_LABEL, instance.getAccount());
            labels.put(SCRAPE_REGION_LABEL, instance.getRegion());
            labels.put("aws_resource_type", "AWS::EC2::Instance");
            labels.put("namespace", "AWS/EC2");
            labels.put("name", instance.getName());
            labels.put("job", instance.getName());
            labels.putAll(tagUtil.tagLabels(instance.getTags()));
            samples.add(metricSampleBuilder.buildSingleSample("aws_resource", labels, 1.0d));
        });

        if (samples.size() > 0) {
            newMetrics.add(metricSampleBuilder.buildFamily(samples));
        }

        resourceMetrics = newMetrics;
        attachedVolumes = newAttachedVolumes;
    }
}
