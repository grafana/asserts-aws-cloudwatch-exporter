/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.account;

import ai.asserts.aws.cluster.HekateCluster;
import ai.asserts.aws.hash.RendezvousHash;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.hash.Funnel;
import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;
import io.hekate.cluster.ClusterNode;
import lombok.extern.slf4j.Slf4j;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@SuppressWarnings("UnstableApiUsage")
public class HekateDistributedAccountProvider implements AccountProvider {
    private static final HashFunction hashFunction = Hashing.murmur3_128();
    private final HekateCluster hekateCluster;
    private final AccountProvider delegate;

    public HekateDistributedAccountProvider(HekateCluster hekateCluster, AccountProvider accountProvider) {
        this.hekateCluster = hekateCluster;
        this.delegate = accountProvider;
    }

    @Override
    public Set<AWSAccount> getAccounts() {
        Set<AWSAccount> accounts = delegate.getAccounts();
        List<String> accountIds = accounts.stream().map(AWSAccount::getAccountId)
                .collect(Collectors.toList());
        log.info("All Account Ids {}", accountIds);
        List<String> selectedAccountIds = pick(accountIds);
        log.info("Selected Account Ids for this node {}", accountIds);
        return accounts.stream()
                .filter(awsAccount -> selectedAccountIds.contains(awsAccount.getAccountId()))
                .collect(Collectors.toSet());
    }

    public List<String> pick(List<String> allKeys) {
        if (hekateCluster.clusterDiscovered()) {
            RendezvousHash<String, String> hash = new RendezvousHash<>(
                    hashFunction,
                    (Funnel<String>) (from, into) -> into.putUnencodedChars(from),
                    (Funnel<String>) (from, into) -> into.putUnencodedChars(from),
                    hekateCluster.allNodes().stream().map(this::clusterNodeToString).collect(Collectors.toList()));

            String localNodeString = clusterNodeToString(hekateCluster.localNode());
            return allKeys.stream()
                    .filter(k -> localNodeString.equals(hash.get(k)))
                    .collect(Collectors.toList());
        }
        return Collections.emptyList();
    }

    @VisibleForTesting
    String clusterNodeToString(ClusterNode node) {
        return node.toString();
    }
}
