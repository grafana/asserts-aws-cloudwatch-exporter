/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.exporter;

import ai.asserts.aws.AWSClientProvider;
import ai.asserts.aws.RateLimiter;
import ai.asserts.aws.TagUtil;
import ai.asserts.aws.account.AccountProvider;
import ai.asserts.aws.resource.Resource;
import ai.asserts.aws.resource.ResourceRelation;
import io.prometheus.client.Collector;
import io.prometheus.client.Collector.MetricFamilySamples.Sample;
import io.prometheus.client.CollectorRegistry;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.DescribeInstancesRequest;
import software.amazon.awssdk.services.ec2.model.DescribeInstancesResponse;
import software.amazon.awssdk.services.ec2.model.DescribeVolumesRequest;
import software.amazon.awssdk.services.ec2.model.DescribeVolumesResponse;
import software.amazon.awssdk.services.ec2.model.Filter;
import software.amazon.awssdk.services.ec2.model.Reservation;
import software.amazon.awssdk.services.resourcegroupstaggingapi.model.Tag;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
import static org.springframework.util.CollectionUtils.isEmpty;
import static software.amazon.awssdk.services.ec2.model.InstanceStateName.RUNNING;

@Slf4j
@Component
public class EC2ToEBSVolumeExporter extends Collector implements MetricProvider, InitializingBean {
    private final AccountProvider accountProvider;
    private final AWSClientProvider awsClientProvider;
    private final MetricSampleBuilder metricSampleBuilder;
    private final CollectorRegistry collectorRegistry;
    private final RateLimiter rateLimiter;

    private final TagUtil tagUtil;

    private final ECSServiceDiscoveryExporter ecsServiceDiscoveryExporter;

    @Getter
    private volatile Set<ResourceRelation> attachedVolumes = new HashSet<>();
    private volatile List<MetricFamilySamples> resourceMetrics = new ArrayList<>();

    public EC2ToEBSVolumeExporter(AccountProvider accountProvider,
                                  AWSClientProvider awsClientProvider, MetricSampleBuilder metricSampleBuilder,
                                  CollectorRegistry collectorRegistry, RateLimiter rateLimiter, TagUtil tagUtil,
                                  ECSServiceDiscoveryExporter ecsServiceDiscoveryExporter) {
        this.accountProvider = accountProvider;
        this.awsClientProvider = awsClientProvider;
        this.metricSampleBuilder = metricSampleBuilder;
        this.collectorRegistry = collectorRegistry;
        this.rateLimiter = rateLimiter;
        this.tagUtil = tagUtil;
        this.ecsServiceDiscoveryExporter = ecsServiceDiscoveryExporter;
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
        //Set<Resource> ec2Instances = new HashSet<>();
        accountProvider.getAccounts().forEach(awsAccount -> awsAccount.getRegions().forEach(region -> {
            try {
                Ec2Client ec2Client = awsClientProvider.getEc2Client(region, awsAccount);
                SortedMap<String, String> telemetryLabels = new TreeMap<>();
                String accountId = awsAccount.getAccountId();

                telemetryLabels.put(SCRAPE_REGION_LABEL, region);
                telemetryLabels.put(SCRAPE_ACCOUNT_ID_LABEL, accountId);

                AtomicReference<String> nextToken = new AtomicReference<>();

                // Get the EC2 Instances
                telemetryLabels.put(SCRAPE_OPERATION_LABEL, "Ec2Client/describeInstances");
                do {
                    DescribeInstancesRequest.Builder reqBuilder = DescribeInstancesRequest.builder();
                    if (!isEmpty(ecsServiceDiscoveryExporter.getSubnetsToScrape())) {
                        reqBuilder = reqBuilder.filters(
                                Filter.builder()
                                        .name("vpc-id")
                                        .values(ecsServiceDiscoveryExporter.getSubnetDetails().get().getVpcId())
                                        .build(),
                                Filter.builder()
                                        .name("subnet-id")
                                        .values(ecsServiceDiscoveryExporter.getSubnetsToScrape())
                                        .build());
                    }
                    DescribeInstancesRequest describeInstancesRequest = reqBuilder
                            .nextToken(nextToken.get())
                            .build();
                    DescribeInstancesResponse response = rateLimiter.doWithRateLimit(
                            "Ec2Client/describeInstances", telemetryLabels,
                            () -> ec2Client.describeInstances(describeInstancesRequest));
                    nextToken.set(response.nextToken());
                    if (response.hasReservations()) {
                        response.reservations().stream()
                                .filter(Reservation::hasInstances)
                                .flatMap(reservation -> reservation.instances().stream())
                                .filter(instance -> instance.state() != null && instance.state().name().equals(RUNNING))
                                .filter(instance -> !isEmpty(instance.tags()) && instance.tags().stream()
                                        .noneMatch(t -> t.key().contains("k8s") || t.key().contains("kubernetes")))
                                .forEach(instance -> {
                                    Map<String, String> labels = new TreeMap<>();
                                    labels.put(SCRAPE_ACCOUNT_ID_LABEL, accountId);
                                    labels.put(SCRAPE_REGION_LABEL, region);
                                    labels.put("aws_resource_type", "AWS::EC2::Instance");
                                    labels.put("namespace", "AWS/EC2");
                                    labels.put("instance_id", instance.instanceId());
                                    labels.put("node", instance.privateDnsName());
                                    labels.put("instance", instance.privateIpAddress());
                                    labels.put("instance_type", instance.instanceTypeAsString());
                                    labels.put("vpc_id", instance.vpcId());
                                    labels.put("subnet_id", instance.subnetId());

                                    labels.putAll(tagUtil.tagLabels(instance.tags().stream()
                                            .map(t -> Tag.builder().key(t.key()).value(t.value()).build())
                                            .collect(Collectors.toList())));
                                    Optional<Sample> opt = metricSampleBuilder.buildSingleSample(
                                            "aws_resource", labels, 1.0d);
                                    opt.ifPresent(samples::add);
                                });
                    }
                } while (nextToken.get() != null);

                nextToken.set(null);
                do {
                    telemetryLabels.put(SCRAPE_OPERATION_LABEL, "Ec2Client/describeVolumes");
                    DescribeVolumesResponse resp =
                            rateLimiter.doWithRateLimit("Ec2Client/describeVolumes", telemetryLabels,
                                    () -> ec2Client.describeVolumes(DescribeVolumesRequest.builder()
                                            .nextToken(nextToken.get())
                                            .build()));
                    if (!isEmpty(resp.volumes())) {
                        newAttachedVolumes.addAll(resp.volumes().stream()
                                .flatMap(volume -> volume.attachments().stream())
                                .map(volumeAttachment -> {
                                    Resource ec2Instance = Resource.builder()
                                            .account(accountId)
                                            .region(region)
                                            .type(EC2Instance)
                                            .name(volumeAttachment.instanceId())
                                            .build();
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
            } catch (Exception e) {
                log.error("Failed to fetch ec2 ebs volumes relation", e);
            }
        }));

        if (samples.size() > 0) {
            metricSampleBuilder.buildFamily(samples).ifPresent(newMetrics::add);
        }

        resourceMetrics = newMetrics;
        attachedVolumes = newAttachedVolumes;
    }
}
