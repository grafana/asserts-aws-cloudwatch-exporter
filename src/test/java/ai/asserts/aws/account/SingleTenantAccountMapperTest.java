/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.account;

import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static ai.asserts.aws.account.SingleInstanceAccountProvider.TSDB_USER_NAME;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class SingleTenantAccountMapperTest {
    @Test
    public void getTenant() {
        SingleTenantAccountMapper accountMapper = new SingleTenantAccountMapper() {
            @Override
            Map<String, String> getGetenv() {
                return ImmutableMap.of(TSDB_USER_NAME, "acme");
            }
        };
        assertEquals("acme", accountMapper.getTenantName("account-1"));
    }
}
