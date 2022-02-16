/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.cloudwatch.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ECSTaskDefScrapeConfigTest {
    @Test
    void validate() {
        ECSTaskDefScrapeConfig config = new ECSTaskDefScrapeConfig()
                .withContainerDefinitionName("container-def")
                .withContainerPort(8000);
        assertTrue(config.validate());

        config = new ECSTaskDefScrapeConfig()
                .withContainerDefinitionName("container-def")
                .withMetricPath("/metric");
        assertTrue(config.validate());

        config = new ECSTaskDefScrapeConfig()
                .withContainerDefinitionName("container-def");
        assertFalse(config.validate());

        config = new ECSTaskDefScrapeConfig()
                .withMetricPath("/metrics");
        assertFalse(config.validate());
    }
}
