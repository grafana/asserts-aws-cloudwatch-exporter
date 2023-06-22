/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws;

import ai.asserts.aws.account.AccountProvider;
import ai.asserts.aws.account.HekateDistributedAccountProvider;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@AllArgsConstructor
public class DeploymentModeUtil {
    private final ScrapeConfigProvider scrapeConfigProvider;
    private final AccountProvider accountProvider;

    public boolean isSingleTenant() {
        return scrapeConfigProvider.getClass().getSimpleName()
                .equals(SingleTenantScrapeConfigProvider.class.getSimpleName());
    }

    public boolean isMultiTenant() {
        return !isSingleTenant();
    }

    public boolean isDistributed() {
        return accountProvider.getClass().getSimpleName()
                .equals(HekateDistributedAccountProvider.class.getSimpleName());
    }

    public boolean isSingleInstance() {
        return !isDistributed();
    }
}
