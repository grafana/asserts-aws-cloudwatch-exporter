/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.cloudwatch.config;

import ai.asserts.aws.resource.Resource;
import com.google.common.collect.ImmutableList;
import org.easymock.EasyMockSupport;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.easymock.EasyMock.expect;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ScrapeConfigTest extends EasyMockSupport {
    @Test
    public void getLambdaConfig_NoNamespaces() {
        ScrapeConfig scrapeConfig = ScrapeConfig.builder()
                .namespaces(ImmutableList.of())
                .build();

        replayAll();
        assertEquals(Optional.empty(), scrapeConfig.getLambdaConfig());
        verifyAll();
    }

    @Test
    public void getLambdaConfig_Exists() {
        NamespaceConfig mockConfig = mock(NamespaceConfig.class);
        ScrapeConfig scrapeConfig = ScrapeConfig.builder()
                .namespaces(ImmutableList.of(mockConfig))
                .build();

        expect(mockConfig.getName()).andReturn("AWS/Lambda");
        expect(mockConfig.getName()).andReturn("lambda");

        replayAll();
        assertEquals(Optional.of(mockConfig), scrapeConfig.getLambdaConfig());
        assertEquals(Optional.of(mockConfig), scrapeConfig.getLambdaConfig());
        verifyAll();
    }

    @Test
    public void getLambdaConfig_None() {
        NamespaceConfig mockConfig = mock(NamespaceConfig.class);
        ScrapeConfig scrapeConfig = ScrapeConfig.builder()
                .namespaces(ImmutableList.of(mockConfig))
                .build();

        expect(mockConfig.getName()).andReturn("AWS/ECS");

        replayAll();
        assertEquals(Optional.empty(), scrapeConfig.getLambdaConfig());
        verifyAll();
    }

    @Test
    public void isECSMonitoringOn_True() {
        ScrapeConfig scrapeConfig = ScrapeConfig.builder()
                .namespaces(ImmutableList.of(NamespaceConfig.builder()
                        .name("AWS/ECS")
                        .build()))
                .build();
        assertTrue(scrapeConfig.isECSMonitoringOn());

        scrapeConfig = ScrapeConfig.builder()
                .namespaces(ImmutableList.of(NamespaceConfig.builder()
                        .name("ECS/ContainerInsights")
                        .build()))
                .build();
        assertTrue(scrapeConfig.isECSMonitoringOn());
    }

    @Test
    public void isECSMonitoringOn_False() {
        ScrapeConfig scrapeConfig = ScrapeConfig.builder()
                .namespaces(ImmutableList.of(NamespaceConfig.builder()
                        .name("AWS/Lambda")
                        .build()))
                .build();
        assertFalse(scrapeConfig.isECSMonitoringOn());

        scrapeConfig = ScrapeConfig.builder().build();
        assertFalse(scrapeConfig.isECSMonitoringOn());
    }

    @Test
    public void getECSScrapeConfig_None() {
        ScrapeConfig scrapeConfig = ScrapeConfig.builder().build();
        assertEquals(Optional.empty(), scrapeConfig.getECSScrapeConfig(Resource.builder().build()));
    }

    @Test
    public void getECSScrapeConfig_Exists() {
        ECSTaskDefScrapeConfig mockConfig = mock(ECSTaskDefScrapeConfig.class);
        ScrapeConfig scrapeConfig = ScrapeConfig.builder()
                .ecsTaskScrapeConfigs(ImmutableList.of(mockConfig))
                .build();
        Resource resource = mock(Resource.class);

        expect(mockConfig.isApplicable(resource)).andReturn(true);

        replayAll();
        assertEquals(Optional.of(mockConfig), scrapeConfig.getECSScrapeConfig(resource));
        verifyAll();
    }
}
