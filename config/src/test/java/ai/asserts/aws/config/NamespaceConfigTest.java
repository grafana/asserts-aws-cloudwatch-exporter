/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.config;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import org.easymock.EasyMockSupport;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.easymock.EasyMock.expect;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class NamespaceConfigTest extends EasyMockSupport {
    @Test
    void getScrapeInterval() {
        ScrapeConfig scrapeConfig = mock(ScrapeConfig.class);
        NamespaceConfig namespaceConfig = NamespaceConfig.builder()
                .scrapeConfig(scrapeConfig)
                .build();

        expect(scrapeConfig.getScrapeInterval()).andReturn(60);
        replayAll();
        assertEquals(60, namespaceConfig.getEffectiveScrapeInterval());
        verifyAll();

        namespaceConfig.setScrapeInterval(120);
        assertEquals(120, namespaceConfig.getEffectiveScrapeInterval());
    }

    @Test
    void validate_noName() {
        NamespaceConfig namespaceConfig = NamespaceConfig.builder()
                .build();
        assertThrows(RuntimeException.class, () -> namespaceConfig.validate(0));
    }

    @Test
    void validate_invalidScrapeInterval() {
        NamespaceConfig namespaceConfig = NamespaceConfig.builder()
                .name("AWS/Lambda")
                .scrapeInterval(5)
                .build();
        assertThrows(RuntimeException.class, () -> namespaceConfig.validate(0));

        namespaceConfig.setScrapeInterval(61);
        assertThrows(RuntimeException.class, () -> namespaceConfig.validate(0));
    }

    @Test
    void validate_invalidPeriod() {
        NamespaceConfig namespaceConfig = NamespaceConfig.builder()
                .name("AWS/Lambda")
                .scrapeInterval(60)
                .period(5)
                .build();
        assertThrows(RuntimeException.class, () -> namespaceConfig.validate(0));

        namespaceConfig.setPeriod(61);
        assertThrows(RuntimeException.class, () -> namespaceConfig.validate(0));
    }

    @Test
    void validate_cascadeValidationCalls() {
        MetricConfig mockMetricConfig = mock(MetricConfig.class);
        LogScrapeConfig logScrapeConfig = mock(LogScrapeConfig.class);

        NamespaceConfig namespaceConfig = NamespaceConfig.builder()
                .name("AWS/Lambda")
                .scrapeInterval(60)
                .period(60)
                .metrics(ImmutableList.of(mockMetricConfig))
                .dimensionFilters(ImmutableMap.of("Dimension", "abc.+"))
                .logs(ImmutableList.of(logScrapeConfig))
                .build();

        mockMetricConfig.setNamespace(namespaceConfig);
        mockMetricConfig.validate(0);
        logScrapeConfig.initialize();

        replayAll();

        namespaceConfig.validate(0);
        assertEquals(1, namespaceConfig.getDimensionFilterPattern().size());
        assertTrue(namespaceConfig.getDimensionFilterPattern().containsKey("Dimension"));
        assertEquals("abc.+", namespaceConfig.getDimensionFilterPattern().get("Dimension").getPattern());
        verifyAll();
    }

    @Test
    public void hasTagFilters() {
        NamespaceConfig namespaceConfig = NamespaceConfig.builder().build();
        assertFalse(namespaceConfig.hasTagFilters());

        namespaceConfig.setTagFilters(Collections.emptyMap());
        assertFalse(namespaceConfig.hasTagFilters());

        namespaceConfig.setTagFilters(ImmutableMap.of("tag", ImmutableSet.of("value")));
        assertTrue(namespaceConfig.hasTagFilters());
    }
}
