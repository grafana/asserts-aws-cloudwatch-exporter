/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.account;

import com.google.common.annotations.VisibleForTesting;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;

import static ai.asserts.aws.account.SingleInstanceAccountProvider.TSDB_USER_NAME;

@Component
@ConditionalOnProperty(name = "tenant.mode", havingValue = "single", matchIfMissing = true)
public class SingleTenantAccountMapper implements AccountTenantMapper{
    @Override
    public String getTenantName(String accountId) {
        return getGetenv().get(TSDB_USER_NAME);
    }

    @VisibleForTesting
    Map<String, String> getGetenv() {
        return System.getenv();
    }
}
