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
    void testOff() {
        assertTrue(new EnvironmentConfig("true").isProcessingOff());
        assertFalse(new EnvironmentConfig("true").isProcessingOn());

        assertTrue(new EnvironmentConfig("yes").isProcessingOff());
        assertFalse(new EnvironmentConfig("yes").isProcessingOn());

        assertTrue(new EnvironmentConfig("y").isProcessingOff());
        assertFalse(new EnvironmentConfig("y").isProcessingOn());
    }

    @Test
    void testOn() {
        assertFalse(new EnvironmentConfig(null).isProcessingOff());
        assertTrue(new EnvironmentConfig(null).isProcessingOn());

        assertFalse(new EnvironmentConfig("false").isProcessingOff());
        assertTrue(new EnvironmentConfig("false").isProcessingOn());

        assertFalse(new EnvironmentConfig("no").isProcessingOff());
        assertTrue(new EnvironmentConfig("no").isProcessingOn());

        assertFalse(new EnvironmentConfig("n").isProcessingOff());
        assertTrue(new EnvironmentConfig("n").isProcessingOn());
    }
}
