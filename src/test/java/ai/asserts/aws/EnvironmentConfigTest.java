/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class EnvironmentConfigTest {
    @Test
    public void singleInstanceMode() {
        EnvironmentConfig util = new EnvironmentConfig("false",
                "multi", "single");
        assertFalse(util.isDistributed());
    }

    @Test
    public void distributedMode() {
        EnvironmentConfig util = new EnvironmentConfig("false",
                "single", "distributed");
        assertTrue(util.isDistributed());
    }

    @Test
    public void singleTenantModel() {
        EnvironmentConfig util = new EnvironmentConfig("false", "single", "single-tenant-single-instance");
        assertTrue(util.isSingleTenant());
    }

    @Test
    public void multiTenantMode() {
        EnvironmentConfig util = new EnvironmentConfig("false",
                "multi", "single-tenant-distributed");
        assertTrue(util.isMultiTenant());
    }

    @Test
    public void processingFlag() {
        EnvironmentConfig util = new EnvironmentConfig("false",
                "multi", "single");
        assertTrue(util.isDisabled());
        assertFalse(util.isEnabled());

        util = new EnvironmentConfig("true",
                "multi", "single");
        assertTrue(util.isEnabled());
        assertFalse(util.isDisabled());
    }
}
