/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.cloudwatch.config;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.resourcegroupstaggingapi.model.Tag;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TagExportConfigTest {
    @Test
    void shouldExportTag_Default() {
        TagExportConfig tagExportConfig = new TagExportConfig();
        assertTrue(tagExportConfig.shouldCaptureTag(Tag.builder()
                .key("name")
                .value("value")
                .build()));
    }

    @Test
    void shouldExportTag_Include() {
        TagExportConfig tagExportConfig = new TagExportConfig();
        tagExportConfig.setIncludePattern("na.+");
        tagExportConfig.compile();
        assertTrue(tagExportConfig.shouldCaptureTag(Tag.builder()
                .key("name")
                .value("value")
                .build()));

        assertFalse(tagExportConfig.shouldCaptureTag(Tag.builder()
                .key("aname")
                .value("value")
                .build()));
    }

    @Test
    void shouldExportTag_Exclude() {
        TagExportConfig tagExportConfig = new TagExportConfig();
        tagExportConfig.setExcludePattern("na.+");
        tagExportConfig.compile();
        assertTrue(tagExportConfig.shouldCaptureTag(Tag.builder()
                .key("kname")
                .value("value")
                .build()));

        assertFalse(tagExportConfig.shouldCaptureTag(Tag.builder()
                .key("name")
                .value("value")
                .build()));
    }

    @Test
    void shouldExportTag_IncludeAndExclude() {
        TagExportConfig tagExportConfig = new TagExportConfig();
        tagExportConfig.setIncludePattern("na.+");
        tagExportConfig.setExcludePattern("nam.+");
        tagExportConfig.compile();
        assertTrue(tagExportConfig.shouldCaptureTag(Tag.builder()
                .key("nano")
                .value("value")
                .build()));

        assertFalse(tagExportConfig.shouldCaptureTag(Tag.builder()
                .key("name")
                .value("value")
                .build()));
    }
}
