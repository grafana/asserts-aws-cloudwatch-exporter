/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.account;

import ai.asserts.aws.cluster.HekateCluster;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import io.hekate.cluster.ClusterNode;
import org.easymock.EasyMockSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.easymock.EasyMock.expect;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class HekateDistributedAccountProviderTest extends EasyMockSupport {
    private SingleInstanceAccountProvider delegate;
    private HekateCluster hekateCluster;
    private ClusterNode clusterNode1;
    private ClusterNode clusterNode2;
    private final Map<ClusterNode, String> clusterNodeToString = new HashMap<>();

    private AWSAccount account1;
    private AWSAccount account2;
    private AWSAccount account3;
    private AWSAccount account4;
    private AWSAccount account5;
    private Set<AWSAccount> allAccounts;
    private HekateDistributedAccountProvider testClass;

    @BeforeEach
    public void setup() {
        hekateCluster = mock(HekateCluster.class);
        delegate = mock(SingleInstanceAccountProvider.class);
        clusterNode1 = mock(ClusterNode.class);
        clusterNode2 = mock(ClusterNode.class);
        clusterNodeToString.put(clusterNode1, "node-1");
        clusterNodeToString.put(clusterNode2, "node-2");
        account1 = AWSAccount.builder()
                .accountId("account-1")
                .build();
        account2 = AWSAccount.builder()
                .accountId("account-2")
                .build();
        account3 = AWSAccount.builder()
                .accountId("account-3")
                .build();
        account4 = AWSAccount.builder()
                .accountId("account-4")
                .build();
        account5 = AWSAccount.builder()
                .accountId("account-5")
                .build();
        allAccounts = ImmutableSet.of(
                account1,
                account2,
                account3,
                account4,
                account5
        );
        testClass = new HekateDistributedAccountProvider(hekateCluster,
                delegate) {
            @Override
            String clusterNodeToString(ClusterNode node) {
                return clusterNodeToString.get(node);
            }
        };
    }

    @Test
    public void getAccounts_ClusterNotDiscovered() {
        expect(delegate.getAccounts()).andReturn(allAccounts);
        expect(hekateCluster.clusterDiscovered()).andReturn(false);
        replayAll();
        assertEquals(Collections.emptySet(), testClass.getAccounts());
        verifyAll();
    }

    @Test
    public void getAccounts_OnlyOneNode() {
        expect(hekateCluster.clusterDiscovered()).andReturn(true);
        expect(delegate.getAccounts()).andReturn(allAccounts);
        expect(hekateCluster.localNode()).andReturn(clusterNode1);
        expect(hekateCluster.allNodes()).andReturn(ImmutableList.of(clusterNode1)).anyTimes();
        replayAll();
        assertEquals(allAccounts, testClass.getAccounts());
        verifyAll();
    }

    @Test
    public void getAccounts_TwoNodes() {
        expect(hekateCluster.clusterDiscovered()).andReturn(true).anyTimes();
        expect(delegate.getAccounts()).andReturn(allAccounts).anyTimes();
        expect(hekateCluster.localNode()).andReturn(clusterNode1);
        expect(hekateCluster.localNode()).andReturn(clusterNode2);
        expect(hekateCluster.allNodes()).andReturn(ImmutableList.of(clusterNode1, clusterNode2)).anyTimes();
        replayAll();
        assertEquals(ImmutableSet.of(account1, account4, account3), testClass.getAccounts());
        assertEquals(ImmutableSet.of(account2, account5), testClass.getAccounts());
        verifyAll();
    }
}
