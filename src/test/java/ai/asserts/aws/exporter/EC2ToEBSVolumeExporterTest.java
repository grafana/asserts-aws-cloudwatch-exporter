/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.exporter;

import ai.asserts.aws.AWSClientProvider;
import ai.asserts.aws.AccountProvider;
import ai.asserts.aws.AccountProvider.AWSAccount;
import ai.asserts.aws.RateLimiter;
import ai.asserts.aws.resource.Resource;
import ai.asserts.aws.resource.ResourceRelation;
import com.google.common.collect.ImmutableSet;
import org.easymock.EasyMockSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.DescribeVolumesRequest;
import software.amazon.awssdk.services.ec2.model.DescribeVolumesResponse;
import software.amazon.awssdk.services.ec2.model.Volume;
import software.amazon.awssdk.services.ec2.model.VolumeAttachment;

import java.util.SortedMap;

import static ai.asserts.aws.MetricNameUtil.SCRAPE_LATENCY_METRIC;
import static ai.asserts.aws.resource.ResourceType.EBSVolume;
import static ai.asserts.aws.resource.ResourceType.EC2Instance;
import static org.easymock.EasyMock.anyLong;
import static org.easymock.EasyMock.anyObject;
import static org.easymock.EasyMock.eq;
import static org.easymock.EasyMock.expect;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class EC2ToEBSVolumeExporterTest extends EasyMockSupport {
    private Ec2Client ec2Client;
    private BasicMetricCollector metricCollector;
    private EC2ToEBSVolumeExporter testClass;

    @BeforeEach
    public void setup() {
        AWSAccount account = new AWSAccount(
                "account", "", "", "role", ImmutableSet.of("region"));
        AccountProvider accountProvider = mock(AccountProvider.class);
        AWSClientProvider awsClientProvider = mock(AWSClientProvider.class);
        ec2Client = mock(Ec2Client.class);
        metricCollector = mock(BasicMetricCollector.class);
        testClass = new EC2ToEBSVolumeExporter(
                accountProvider, awsClientProvider, new RateLimiter(metricCollector)
        );
        expect(accountProvider.getAccounts()).andReturn(ImmutableSet.of(account));
        expect(awsClientProvider.getEc2Client("region", account)).andReturn(ec2Client);
    }

    @Test
    @SuppressWarnings("unchecked")
    public void update() {
        DescribeVolumesRequest req = DescribeVolumesRequest.builder().build();
        DescribeVolumesResponse resp = DescribeVolumesResponse.builder()
                .volumes(Volume.builder()
                        .attachments(VolumeAttachment.builder()
                                .volumeId("volume")
                                .instanceId("instance")
                                .build())
                        .build())
                .build();
        expect(ec2Client.describeVolumes(req)).andReturn(resp);
        metricCollector.recordLatency(eq(SCRAPE_LATENCY_METRIC), anyObject(SortedMap.class), anyLong());
        replayAll();
        testClass.update();
        assertEquals(ImmutableSet.of(
                ResourceRelation.builder()
                        .from(Resource.builder()
                                .name("volume")
                                .type(EBSVolume)
                                .account("account")
                                .region("region")
                                .build())
                        .to(Resource.builder()
                                .name("instance")
                                .region("region")
                                .account("account")
                                .type(EC2Instance)
                                .build())
                        .name("ATTACHED_TO")
                        .build()
        ), testClass.getAttachedVolumes());
        verifyAll();
    }
}
