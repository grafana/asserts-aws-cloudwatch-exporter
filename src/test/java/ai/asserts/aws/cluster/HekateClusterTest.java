/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.cluster;

import com.google.common.collect.ImmutableList;
import io.hekate.cluster.ClusterNode;
import io.hekate.cluster.ClusterTopology;
import io.hekate.cluster.event.ClusterEvent;
import org.easymock.EasyMockSupport;
import org.junit.jupiter.api.Test;

import static org.easymock.EasyMock.expect;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class HekateClusterTest extends EasyMockSupport {
    @Test
    public void clusterSetup() {
        ClusterEvent clusterEvent = mock(ClusterEvent.class);
        ClusterTopology clusterTopology = mock(ClusterTopology.class);
        ClusterNode node1 = mock(ClusterNode.class);
        ClusterNode node2 = mock(ClusterNode.class);
        ImmutableList<ClusterNode> allNodes = ImmutableList.of(node1, node2);
        HekateCluster hekateCluster = new HekateCluster();

        expect(clusterEvent.topology()).andReturn(clusterTopology);
        expect(clusterTopology.localNode()).andReturn(node1);
        expect(clusterTopology.nodes()).andReturn(allNodes);

        replayAll();
        assertFalse(hekateCluster.clusterDiscovered());
        hekateCluster.onEvent(clusterEvent);
        assertEquals(node1, hekateCluster.localNode());
        assertEquals(allNodes, hekateCluster.allNodes());
        assertTrue(hekateCluster.clusterDiscovered());
        verifyAll();
    }
}
