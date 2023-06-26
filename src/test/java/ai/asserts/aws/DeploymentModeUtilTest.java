/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class DeploymentModeUtilTest {
    @Test
    public void singleInstanceMode() {
        DeploymentModeUtil util = new DeploymentModeUtil("multi-tenant", "single-tenant-single-instance");
        assertFalse(util.isDistributed());
    }

    @Test
    public void distributedMode() {
        DeploymentModeUtil util = new DeploymentModeUtil("multi-tenant", "single-tenant-distributed");
        assertTrue(util.isDistributed());
    }

    @Test
    public void singleTenantModel() {
        DeploymentModeUtil util = new DeploymentModeUtil("single", "single-tenant-single-instance");
        assertTrue(util.isSingleTenant());
    }

    @Test
    public void multiTenantMode() {
        DeploymentModeUtil util = new DeploymentModeUtil("multi-tenant", "single-tenant-distributed");
        assertTrue(util.isMultiTenant());
    }
}
