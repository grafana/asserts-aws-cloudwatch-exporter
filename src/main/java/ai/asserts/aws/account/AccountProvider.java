/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.account;

import java.util.Set;

/**
 * Provides a list of AWS Accounts that this instance of the Exporter will process.
 */
public interface AccountProvider {
    Set<AWSAccount> getAccounts();
}
