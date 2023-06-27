/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.account;

import java.util.Collections;
import java.util.Set;

public class NoopAccountProvider implements AccountProvider {
    @Override
    public Set<AWSAccount> getAccounts() {
        return Collections.emptySet();
    }
}
