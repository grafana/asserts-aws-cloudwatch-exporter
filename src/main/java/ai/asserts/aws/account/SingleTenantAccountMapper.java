/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.account;

import com.google.common.annotations.VisibleForTesting;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;

import static ai.asserts.aws.account.SingleInstanceAccountProvider.TSDB_USER_NAME;

@Component
@ConditionalOnProperty(name = "aws_exporter.tenant_mode", havingValue = "single", matchIfMissing = true)
@Slf4j
public class SingleTenantAccountMapper implements AccountTenantMapper {
    public SingleTenantAccountMapper() {
        log.info("Single Tenant Account Mapper created");
    }

    @Override
    public String getTenantName(String accountId) {
        return getGetenv().get(TSDB_USER_NAME);
    }

    @VisibleForTesting
    Map<String, String> getGetenv() {
        return System.getenv();
    }
}
