/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.config;

import com.google.common.collect.ImmutableSet;
import org.easymock.EasyMockSupport;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TagExportConfigTest extends EasyMockSupport {
    @Test
    void shouldExportTag_Default() {
        TagExportConfig tagExportConfig = new TagExportConfig();
        assertTrue(tagExportConfig.shouldCaptureTag("name", "value"));
    }

    @Test
    void shouldExportTag_Include() {
        TagExportConfig tagExportConfig = new TagExportConfig();
        tagExportConfig.setIncludePatterns(ImmutableSet.of("na.+"));
        tagExportConfig.compile();
        assertTrue(tagExportConfig.shouldCaptureTag("name", "value"));

        assertFalse(tagExportConfig.shouldCaptureTag("aname", "value"));
    }

    @Test
    void shouldExportTag_Exclude() {
        TagExportConfig tagExportConfig = new TagExportConfig();
        tagExportConfig.setExcludePatterns(ImmutableSet.of("na.+"));
        tagExportConfig.compile();
        assertTrue(tagExportConfig.shouldCaptureTag("kname", "value"));

        assertFalse(tagExportConfig.shouldCaptureTag("name", "value"));
    }

    @Test
    void shouldExportTag_IncludeAndExclude() {
        TagExportConfig tagExportConfig = new TagExportConfig();
        tagExportConfig.setIncludePatterns(ImmutableSet.of("na.+"));
        tagExportConfig.setExcludePatterns(ImmutableSet.of("nam.+"));
        tagExportConfig.compile();
        assertTrue(tagExportConfig.shouldCaptureTag("nano", "value"));

        assertFalse(tagExportConfig.shouldCaptureTag("name", "value"));
    }
}
