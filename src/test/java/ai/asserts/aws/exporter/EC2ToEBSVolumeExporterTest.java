/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.exporter;

import ai.asserts.aws.AWSApiCallRateLimiter;
import ai.asserts.aws.AWSClientProvider;
import ai.asserts.aws.ScrapeConfigProvider;
import ai.asserts.aws.TagUtil;
import ai.asserts.aws.TaskExecutorUtil;
import ai.asserts.aws.TestTaskThreadPool;
import ai.asserts.aws.account.AWSAccount;
import ai.asserts.aws.account.AccountProvider;
import ai.asserts.aws.config.ScrapeConfig;
import ai.asserts.aws.resource.Resource;
import ai.asserts.aws.resource.ResourceRelation;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import io.prometheus.client.Collector.MetricFamilySamples;
import io.prometheus.client.Collector.MetricFamilySamples.Sample;
import io.prometheus.client.CollectorRegistry;
import org.easymock.EasyMockSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.DescribeInstancesRequest;
import software.amazon.awssdk.services.ec2.model.DescribeInstancesResponse;
import software.amazon.awssdk.services.ec2.model.DescribeVolumesRequest;
import software.amazon.awssdk.services.ec2.model.DescribeVolumesResponse;
import software.amazon.awssdk.services.ec2.model.Filter;
import software.amazon.awssdk.services.ec2.model.Instance;
import software.amazon.awssdk.services.ec2.model.InstanceState;
import software.amazon.awssdk.services.ec2.model.InstanceStateName;
import software.amazon.awssdk.services.ec2.model.InstanceType;
import software.amazon.awssdk.services.ec2.model.Reservation;
import software.amazon.awssdk.services.ec2.model.Volume;
import software.amazon.awssdk.services.ec2.model.VolumeAttachment;
import software.amazon.awssdk.services.resourcegroupstaggingapi.model.Tag;

import java.util.Optional;
import java.util.SortedMap;
import java.util.concurrent.atomic.AtomicReference;

import static ai.asserts.aws.MetricNameUtil.SCRAPE_LATENCY_METRIC;
import static ai.asserts.aws.resource.ResourceType.EBSVolume;
import static ai.asserts.aws.resource.ResourceType.EC2Instance;
import static org.easymock.EasyMock.anyLong;
import static org.easymock.EasyMock.anyObject;
import static org.easymock.EasyMock.eq;
import static org.easymock.EasyMock.expect;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class EC2ToEBSVolumeExporterTest extends EasyMockSupport {
    private AWSAccount account;
    private AccountProvider accountProvider;
    private AWSClientProvider awsClientProvider;
    private Ec2Client ec2Client;
    private BasicMetricCollector metricCollector;
    private MetricSampleBuilder metricSampleBuilder;
    private MetricFamilySamples metricFamilySamples;
    private CollectorRegistry collectorRegistry;
    private Sample sample;
    private EC2ToEBSVolumeExporter testClass;
    private ECSServiceDiscoveryExporter ecsServiceDiscoveryExporter;
    private ScrapeConfigProvider scrapeConfigProvider;
    private ScrapeConfig scrapeConfig;
    private TagUtil tagUtil;

    @BeforeEach
    public void setup() {
        account = new AWSAccount(
                "acme", "account", "", "", "role", ImmutableSet.of("region"));
        accountProvider = mock(AccountProvider.class);
        awsClientProvider = mock(AWSClientProvider.class);
        ec2Client = mock(Ec2Client.class);
        metricCollector = mock(BasicMetricCollector.class);
        metricSampleBuilder = mock(MetricSampleBuilder.class);
        metricFamilySamples = mock(MetricFamilySamples.class);
        sample = mock(Sample.class);
        collectorRegistry = mock(CollectorRegistry.class);
        ecsServiceDiscoveryExporter = mock(ECSServiceDiscoveryExporter.class);
        tagUtil = mock(TagUtil.class);
        scrapeConfigProvider = mock(ScrapeConfigProvider.class);
        scrapeConfig = mock(ScrapeConfig.class);
        AWSApiCallRateLimiter rateLimiter = new AWSApiCallRateLimiter(metricCollector, (account) -> "acme");
        testClass = new EC2ToEBSVolumeExporter(accountProvider, awsClientProvider, metricSampleBuilder,
                collectorRegistry, rateLimiter, tagUtil, ecsServiceDiscoveryExporter,
                new TaskExecutorUtil(new TestTaskThreadPool(), rateLimiter), scrapeConfigProvider);
    }

    @Test
    public void afterPropertiesSet() throws Exception {
        collectorRegistry.register(testClass);
        replayAll();
        testClass.afterPropertiesSet();
        verifyAll();
    }

    @Test
    @SuppressWarnings("unchecked")
    public void updateCollect() {
        expect(accountProvider.getAccounts()).andReturn(ImmutableSet.of(account));
        expect(awsClientProvider.getEc2Client("region", account)).andReturn(ec2Client).anyTimes();
        expect(scrapeConfigProvider.getScrapeConfig("acme")).andReturn(scrapeConfig).anyTimes();
        expect(scrapeConfig.isFetchEC2Metadata()).andReturn(true).anyTimes();
        DescribeInstancesRequest request = DescribeInstancesRequest.builder()
                .filters(Filter.builder()
                                .name("vpc-id")
                                .values("vpc-id")
                                .build(),
                        Filter.builder()
                                .name("subnet-id")
                                .values("subnet-id")
                                .build())
                .build();
        DescribeInstancesResponse response = DescribeInstancesResponse.builder()
                .reservations(Reservation.builder()
                        .instances(Instance.builder()
                                        .vpcId("vpc-id")
                                        .subnetId("subnet-id")
                                        .privateDnsName("dns-name")
                                        .privateIpAddress("1.2.3.4")
                                        .instanceId("instance-id")
                                        .instanceType(InstanceType.M1_LARGE)
                                        .instanceType(InstanceType.M1_LARGE.name())
                                        .state(InstanceState.builder().name(InstanceStateName.RUNNING).build())
                                        .tags(software.amazon.awssdk.services.ec2.model.Tag.builder()
                                                        .key("k").value("v")
                                                        .build(),
                                                software.amazon.awssdk.services.ec2.model.Tag.builder()
                                                        .key("Name").value("instance-name")
                                                        .build())
                                        .build(),
                                Instance.builder()
                                        .vpcId("vpc-id")
                                        .subnetId("subnet-id")
                                        .privateDnsName("dns-name2")
                                        .privateIpAddress("1.2.3.5")
                                        .instanceId("instance-id2")
                                        .instanceType(InstanceType.M1_LARGE)
                                        .instanceType(InstanceType.M1_LARGE.name())
                                        .tags(software.amazon.awssdk.services.ec2.model.Tag.builder()
                                                        .key("k8s").value("v")
                                                        .build(),
                                                software.amazon.awssdk.services.ec2.model.Tag.builder()
                                                        .key("Name").value("instance-name")
                                                        .build())
                                        .build(),
                                Instance.builder()
                                        .vpcId("vpc-id")
                                        .subnetId("subnet-id")
                                        .privateDnsName("dns-name2")
                                        .privateIpAddress("1.2.3.5")
                                        .instanceId("instance-id2")
                                        .instanceType(InstanceType.M1_LARGE)
                                        .instanceType(InstanceType.M1_LARGE.name())
                                        .state(InstanceState.builder().name(InstanceStateName.STOPPED).build())
                                        .tags(software.amazon.awssdk.services.ec2.model.Tag.builder()
                                                .key("Name").value("instance-name")
                                                .build())
                                        .build())
                        .build())
                .build();
        expect(ec2Client.describeInstances(request)).andReturn(response);
        ImmutableList<Tag> tags = ImmutableList.of(
                Tag.builder().key("k").value("v").build(),
                Tag.builder().key("Name").value("instance-name").build());
        expect(tagUtil.tagLabels(scrapeConfig, tags)).andReturn(
                ImmutableMap.of("tag_k", "v")
        );
        metricCollector.recordLatency(eq(SCRAPE_LATENCY_METRIC), anyObject(SortedMap.class), anyLong());

        DescribeVolumesRequest req = DescribeVolumesRequest.builder().build();
        DescribeVolumesResponse resp = DescribeVolumesResponse.builder()
                .volumes(Volume.builder()
                        .attachments(VolumeAttachment.builder()
                                .volumeId("volume")
                                .instanceId("instance-id")
                                .build())
                        .build())
                .build();
        expect(ec2Client.describeVolumes(req)).andReturn(resp);
        metricCollector.recordLatency(eq(SCRAPE_LATENCY_METRIC), anyObject(SortedMap.class), anyLong());
        expect(metricSampleBuilder.buildSingleSample("aws_resource",
                new ImmutableMap.Builder<String, String>()
                        .put("account_id", "account")
                        .put("vpc_id", "vpc-id")
                        .put("subnet_id", "subnet-id")
                        .put("region", "region")
                        .put("aws_resource_type", "AWS::EC2::Instance")
                        .put("instance_id", "instance-id")
                        .put("instance_type", "M1_LARGE")
                        .put("node", "dns-name")
                        .put("instance", "1.2.3.4")
                        .put("namespace", "AWS/EC2")
                        .put("tag_k", "v")
                        .build()
                , 1.0d))
                .andReturn(Optional.of(sample));


        expect(metricSampleBuilder.buildFamily(ImmutableList.of(sample))).andReturn(Optional.of(metricFamilySamples));
        expect(ecsServiceDiscoveryExporter.getSubnetDetails()).andReturn(new AtomicReference<>(
                ScrapeConfig.SubnetDetails.builder()
                        .vpcId("vpc-id")
                        .build())).anyTimes();
        expect(ecsServiceDiscoveryExporter.getSubnetsToScrape()).andReturn(ImmutableSet.of("subnet-id")).anyTimes();
        replayAll();
        testClass.update();
        assertEquals(ImmutableSet.of(
                ResourceRelation.builder()
                        .tenant("acme")
                        .from(Resource.builder()
                                .tenant("acme")
                                .name("volume")
                                .type(EBSVolume)
                                .account("account")
                                .region("region")
                                .build())
                        .to(Resource.builder()
                                .tenant("acme")
                                .name("instance-id")
                                .region("region")
                                .account("account")
                                .type(EC2Instance)
                                .build())
                        .name("ATTACHED_TO")
                        .build()
        ), testClass.getAttachedVolumes());
        assertEquals(ImmutableList.of(metricFamilySamples), testClass.collect());
        verifyAll();
    }

    @Test
    public void updateCollect_skipEC2MetaFetch() {
        expect(accountProvider.getAccounts()).andReturn(ImmutableSet.of(account));
        expect(scrapeConfigProvider.getScrapeConfig("acme")).andReturn(scrapeConfig).anyTimes();
        expect(scrapeConfig.isFetchEC2Metadata()).andReturn(false).anyTimes();

        replayAll();
        testClass.update();
        assertEquals(ImmutableSet.of(), testClass.getAttachedVolumes());
        assertEquals(ImmutableList.of(), testClass.collect());
        verifyAll();
    }
}
