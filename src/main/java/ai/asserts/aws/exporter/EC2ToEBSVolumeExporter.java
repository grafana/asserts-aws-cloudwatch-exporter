/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.exporter;

import ai.asserts.aws.AWSClientProvider;
import ai.asserts.aws.AccountProvider;
import ai.asserts.aws.RateLimiter;
import ai.asserts.aws.resource.Resource;
import ai.asserts.aws.resource.ResourceRelation;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.DescribeVolumesRequest;
import software.amazon.awssdk.services.ec2.model.DescribeVolumesResponse;

import java.util.HashSet;
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

@Slf4j
@Component
public class EC2ToEBSVolumeExporter {
    private final AccountProvider accountProvider;
    private final AWSClientProvider awsClientProvider;
    private final RateLimiter rateLimiter;

    @Getter
    private volatile Set<ResourceRelation> attachedVolumes = new HashSet<>();

    public EC2ToEBSVolumeExporter(AccountProvider accountProvider, AWSClientProvider awsClientProvider,
                                  RateLimiter rateLimiter) {
        this.accountProvider = accountProvider;
        this.awsClientProvider = awsClientProvider;
        this.rateLimiter = rateLimiter;
    }

    public void update() {
        log.info("Export EBS Volumes attached to EC2 Instances");
        Set<ResourceRelation> newAttachedVolumes = new HashSet<>();
        accountProvider.getAccounts().forEach(awsAccount -> awsAccount.getRegions().forEach(region -> {
            try {
                Ec2Client ec2Client = awsClientProvider.getEc2Client(region, awsAccount);
                SortedMap<String, String> telemetryLabels = new TreeMap<>();
                String api = "Ec2Client/describeVolumes";
                telemetryLabels.put(SCRAPE_OPERATION_LABEL, api);
                telemetryLabels.put(SCRAPE_REGION_LABEL, region);
                String accountId = awsAccount.getAccountId();
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
                                .map(volumeAttachment -> ResourceRelation.builder()
                                        .from(Resource.builder()
                                                .account(accountId)
                                                .region(region)
                                                .type(EBSVolume)
                                                .name(volumeAttachment.volumeId())
                                                .build())
                                        .to(Resource.builder()
                                                .account(accountId)
                                                .region(region)
                                                .type(EC2Instance)
                                                .name(volumeAttachment.instanceId())
                                                .build())
                                        .name("ATTACHED_TO")
                                        .build())
                                .collect(Collectors.toSet()));
                    }
                    nextToken.set(resp.nextToken());
                } while (nextToken.get() != null);
            } catch (Exception e) {
                log.error("Failed to fetch ec2 ebs volumes relation", e);
            }
        }));
        attachedVolumes = newAttachedVolumes;
    }
}
