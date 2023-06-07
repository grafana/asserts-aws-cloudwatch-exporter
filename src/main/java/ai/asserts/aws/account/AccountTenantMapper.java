/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.account;

public interface AccountTenantMapper {
    String getTenantName(String accountId);
}
