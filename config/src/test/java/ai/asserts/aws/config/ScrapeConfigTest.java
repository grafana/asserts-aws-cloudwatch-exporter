/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.config;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.easymock.EasyMockSupport;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

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
    void shouldExportTag_DefaultNone() {
        ScrapeConfig scrapeConfig = ScrapeConfig.builder()
                .build();

        replayAll();
        assertFalse(scrapeConfig.shouldExportTag("tag", "value"));
        verifyAll();
    }

    @Test
    void shouldExportTag() {
        TagExportConfig tagExportConfig = mock(TagExportConfig.class);
        ScrapeConfig scrapeConfig = ScrapeConfig.builder()
                .tagExportConfig(tagExportConfig)
                .build();

        expect(tagExportConfig.shouldCaptureTag("tag", "value")).andReturn(true);

        replayAll();
        assertTrue(scrapeConfig.shouldExportTag("tag", "value"));
        verifyAll();
    }
}
