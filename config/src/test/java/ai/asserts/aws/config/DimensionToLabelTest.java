/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.config;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class DimensionToLabelTest {
    @Test
    public void getValue() {
        DimensionToLabel dimensionToLabel = new DimensionToLabel();
        dimensionToLabel.compile();
        assertEquals(Optional.of("Foo"), dimensionToLabel.getValue("Foo"));

        dimensionToLabel.setRegex("(.+?)-[0-9]+");
        dimensionToLabel.compile();
        dimensionToLabel.setValueExp("$1-Bar");
        assertEquals(Optional.of("Foo-Bar"), dimensionToLabel.getValue("Foo-001"));
    }
}
