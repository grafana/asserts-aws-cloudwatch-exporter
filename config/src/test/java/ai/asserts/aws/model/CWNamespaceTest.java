/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class CWNamespaceTest {
    @Test
    void isThisNamespace() {
        assertTrue(CWNamespace.lambda.isThisNamespace("AWS/Lambda"));
        assertTrue(CWNamespace.lambda.isThisNamespace("lambda"));
        assertFalse(CWNamespace.lambda.isThisNamespace("AWS/ECS"));
    }
}
