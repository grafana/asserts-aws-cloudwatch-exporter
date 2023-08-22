/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.exporter;

import ai.asserts.aws.AWSApiCallRateLimiter;
import ai.asserts.aws.AWSClientProvider;
import ai.asserts.aws.CollectionBuilderTask;
import ai.asserts.aws.ScrapeConfigProvider;
import ai.asserts.aws.TagUtil;
import ai.asserts.aws.TaskExecutorUtil;
import ai.asserts.aws.account.AWSAccount;
import ai.asserts.aws.account.AccountProvider;
import ai.asserts.aws.config.ScrapeConfig;
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
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.Future;
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
    private final AWSApiCallRateLimiter rateLimiter;
    private final TagUtil tagUtil;
    private final ECSServiceDiscoveryExporter ecsServiceDiscoveryExporter;
    private final TaskExecutorUtil taskExecutorUtil;
    private final ScrapeConfigProvider scrapeConfigProvider;
    @Getter
    private volatile Set<ResourceRelation> attachedVolumes = new HashSet<>();
    private volatile List<MetricFamilySamples> resourceMetrics = new ArrayList<>();

    public EC2ToEBSVolumeExporter(AccountProvider accountProvider,
                                  AWSClientProvider awsClientProvider, MetricSampleBuilder metricSampleBuilder,
                                  CollectorRegistry collectorRegistry, AWSApiCallRateLimiter rateLimiter,
                                  TagUtil tagUtil,
                                  ECSServiceDiscoveryExporter ecsServiceDiscoveryExporter,
                                  TaskExecutorUtil taskExecutorUtil, ScrapeConfigProvider scrapeConfigProvider) {
        this.accountProvider = accountProvider;
        this.awsClientProvider = awsClientProvider;
        this.metricSampleBuilder = metricSampleBuilder;
        this.collectorRegistry = collectorRegistry;
        this.rateLimiter = rateLimiter;
        this.tagUtil = tagUtil;
        this.ecsServiceDiscoveryExporter = ecsServiceDiscoveryExporter;
        this.taskExecutorUtil = taskExecutorUtil;
        this.scrapeConfigProvider = scrapeConfigProvider;
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
        List<Sample> allSamples = new ArrayList<>();
        List<MetricFamilySamples> newMetrics = new ArrayList<>();
        Set<ResourceRelation> newAttachedVolumes = new HashSet<>();
        List<Future<List<Sample>>> futures = new ArrayList<>();
        List<Future<List<ResourceRelation>>> volumeFutures = new ArrayList<>();
        accountProvider.getAccounts().forEach(awsAccount -> awsAccount.getRegions().forEach(region -> {
            futures.add(taskExecutorUtil.executeAccountTask(awsAccount, new CollectionBuilderTask<Sample>() {
                @Override
                public List<Sample> call() {
                    return buildEC2InstanceMetrics(region, awsAccount);
                }
            }));
            volumeFutures.add(
                    taskExecutorUtil.executeAccountTask(awsAccount,
                            new CollectionBuilderTask<ResourceRelation>() {
                                @Override
                                public List<ResourceRelation> call() {
                                    return buildResourceRelations(awsAccount, region);
                                }
                            }));
        }));
        taskExecutorUtil.awaitAll(futures, allSamples::addAll);
        taskExecutorUtil.awaitAll(volumeFutures, newAttachedVolumes::addAll);
        if (allSamples.size() > 0) {
            metricSampleBuilder.buildFamily(allSamples).ifPresent(newMetrics::add);
        }

        resourceMetrics = newMetrics;
        attachedVolumes = newAttachedVolumes;
    }

    private List<ResourceRelation> buildResourceRelations(AWSAccount awsAccount, String region) {
        ScrapeConfig scrapeConfig = scrapeConfigProvider.getScrapeConfig(awsAccount.getTenant());
        if (!scrapeConfig.isFetchEC2Metadata()) {
            log.info("Skipping EC2 Metadata fetch");
            return Collections.emptyList();
        }

        Set<ResourceRelation> newAttachedVolumes = new HashSet<>();
        String accountId = awsAccount.getAccountId();
        Ec2Client ec2Client = awsClientProvider.getEc2Client(region, awsAccount);
        try {
            AtomicReference<String> nextToken = new AtomicReference<>();
            do {

                SortedMap<String, String> telemetryLabels = new TreeMap<>();
                telemetryLabels.put(SCRAPE_REGION_LABEL, accountId);

                telemetryLabels.put(SCRAPE_ACCOUNT_ID_LABEL, accountId);
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
                                String tenant = taskExecutorUtil.getAccountDetails().getTenant();
                                Resource ec2Instance = Resource.builder()
                                        .tenant(tenant)
                                        .account(accountId)
                                        .region(region)
                                        .type(EC2Instance)
                                        .name(volumeAttachment.instanceId())
                                        .build();
                                return ResourceRelation.builder()
                                        .tenant(tenant)
                                        .from(Resource.builder()
                                                .tenant(tenant)
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
        return new ArrayList<>(newAttachedVolumes);
    }

    private List<Sample> buildEC2InstanceMetrics(String region, AWSAccount awsAccount) {
        List<Sample> samples = new ArrayList<>();
        ScrapeConfig scrapeConfig = scrapeConfigProvider.getScrapeConfig(awsAccount.getTenant());
        if (!scrapeConfig.isFetchEC2Metadata()) {
            log.info("Skipping EC2 Metadata fetch");
            return samples;
        }
        Ec2Client ec2Client = awsClientProvider.getEc2Client(region, awsAccount);
        String accountId = awsAccount.getAccountId();
        SortedMap<String, String> telemetryLabels = new TreeMap<>();
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
                        .filter(instance -> instance.state() != null && instance.state().name()
                                .equals(RUNNING))
                        .filter(instance -> !isEmpty(instance.tags()) && instance.tags().stream()
                                .noneMatch(
                                        t -> t.key().contains("k8s") || t.key().contains("kubernetes")))
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

                            labels.putAll(tagUtil.tagLabels(scrapeConfig, instance.tags().stream()
                                    .map(t -> Tag.builder().key(t.key()).value(t.value()).build())
                                    .collect(Collectors.toList())));
                            Optional<Sample> opt = metricSampleBuilder.buildSingleSample(
                                    "aws_resource", labels, 1.0d);
                            opt.ifPresent(samples::add);
                        });
            }
        } while (nextToken.get() != null);
        return samples;
    }
}
