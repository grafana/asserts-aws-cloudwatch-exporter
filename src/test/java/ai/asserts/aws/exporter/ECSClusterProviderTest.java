/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.exporter;

import ai.asserts.aws.AWSClientProvider;
import ai.asserts.aws.account.AWSAccount;
import ai.asserts.aws.AWSApiCallRateLimiter;
import ai.asserts.aws.resource.Resource;
import ai.asserts.aws.resource.ResourceMapper;
import com.google.common.collect.ImmutableSet;
import org.easymock.EasyMockSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.ecs.EcsClient;
import software.amazon.awssdk.services.ecs.model.Cluster;
import software.amazon.awssdk.services.ecs.model.DescribeClustersRequest;
import software.amazon.awssdk.services.ecs.model.DescribeClustersResponse;
import software.amazon.awssdk.services.ecs.model.ListClustersResponse;

import java.util.Optional;
import java.util.SortedMap;

import static org.easymock.EasyMock.anyLong;
import static org.easymock.EasyMock.anyObject;
import static org.easymock.EasyMock.eq;
import static org.easymock.EasyMock.expect;
import static org.junit.jupiter.api.Assertions.assertEquals;

@SuppressWarnings("unchecked")
public class ECSClusterProviderTest extends EasyMockSupport {
    private AWSClientProvider awsClientProvider;
    private EcsClient ecsClient;
    private ResourceMapper resourceMapper;
    private BasicMetricCollector metricCollector;
    private ECSClusterProvider testClass;
    private Resource clusterResource1;
    private Resource clusterResource2;

    @BeforeEach
    public void setup() {
        awsClientProvider = mock(AWSClientProvider.class);
        resourceMapper = mock(ResourceMapper.class);
        metricCollector = mock(BasicMetricCollector.class);
        ecsClient = mock(EcsClient.class);
        clusterResource1 = mock(Resource.class);
        clusterResource2 = mock(Resource.class);
        testClass = new ECSClusterProvider(awsClientProvider,
                new AWSApiCallRateLimiter(metricCollector, (account) -> "acme"), resourceMapper);
    }

    @Test
    public void getClusters() {
        AWSAccount awsAccount = AWSAccount.builder()
                .name("test-account")
                .regions(ImmutableSet.of("region"))
                .accountId("account-1")
                .build();
        expect(awsClientProvider.getECSClient("region", awsAccount)).andReturn(ecsClient);
        ListClustersResponse listClustersResponse = ListClustersResponse.builder()
                .clusterArns("cluster-arn1", "cluster-arn2")
                .build();
        expect(ecsClient.listClusters()).andReturn(listClustersResponse);
        metricCollector.recordLatency(eq("aws_exporter_milliseconds"), anyObject(SortedMap.class), anyLong());

        expect(ecsClient.describeClusters(DescribeClustersRequest.builder()
                .clusters(ImmutableSet.of("cluster-arn1", "cluster-arn2"))
                .build())).andReturn(
                DescribeClustersResponse.builder()
                        .clusters(
                                Cluster.builder().clusterArn("cluster-arn1").status("ACTIVE").build(),
                                Cluster.builder().clusterArn("cluster-arn2").status("ACTIVE").build(),
                                Cluster.builder().clusterArn("cluster-arn3").status("INACTIVE").build(),
                                Cluster.builder().clusterArn("cluster-arn4").status("PROVISIONING").build(),
                                Cluster.builder().clusterArn("cluster-arn5").status("DEPROVISIONING").build(),
                                Cluster.builder().clusterArn("cluster-arn6").status("FAILED").build()
                        )
                        .build()
        );
        metricCollector.recordLatency(eq("aws_exporter_milliseconds"), anyObject(SortedMap.class), anyLong());
        expect(resourceMapper.map("cluster-arn1")).andReturn(Optional.of(clusterResource1));
        expect(resourceMapper.map("cluster-arn2")).andReturn(Optional.of(clusterResource2));
        replayAll();
        assertEquals(ImmutableSet.of(clusterResource1, clusterResource2), testClass.getClusters(awsAccount, "region"));
        verifyAll();
    }
}
