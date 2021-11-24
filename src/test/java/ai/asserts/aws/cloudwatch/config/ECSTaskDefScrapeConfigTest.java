/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.cloudwatch.config;

import ai.asserts.aws.resource.Resource;
import org.junit.jupiter.api.Test;

import static ai.asserts.aws.resource.ResourceType.ECSTaskDef;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ECSTaskDefScrapeConfigTest {
    @Test
    public void isApplicable_false() {
        ECSTaskDefScrapeConfig config = new ECSTaskDefScrapeConfig()
                .withTaskDefinitionName("task-def");
        assertFalse(config.isApplicable(Resource.builder()
                .type(ECSTaskDef)
                .name("task-def1")
                .build()));
    }

    @Test
    public void isApplicable_true() {
        ECSTaskDefScrapeConfig config = new ECSTaskDefScrapeConfig()
                .withTaskDefinitionName("task-def");
        assertTrue(config.isApplicable(Resource.builder()
                .type(ECSTaskDef)
                .name("task-def")
                .build()));
    }
}
