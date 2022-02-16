/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.cloudwatch.config;

import ai.asserts.aws.MetricNameUtil;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import org.easymock.EasyMockSupport;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.resourcegroupstaggingapi.model.Tag;

import java.util.Optional;

import static org.easymock.EasyMock.expect;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TagExportConfigTest extends EasyMockSupport {
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
        tagExportConfig.setIncludePatterns(ImmutableSet.of("na.+"));
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
        tagExportConfig.setExcludePatterns(ImmutableSet.of("na.+"));
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
        tagExportConfig.setIncludePatterns(ImmutableSet.of("na.+"));
        tagExportConfig.setExcludePatterns(ImmutableSet.of("nam.+"));
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

    @Test
    void tagLabels() {
        TagExportConfig tagExportConfig = new TagExportConfig();
        tagExportConfig.setIncludeTags(ImmutableSet.of("kubernetes.io/service_name"));
        MetricNameUtil metricNameUtil = mock(MetricNameUtil.class);
        expect(metricNameUtil.toSnakeCase("kubernetes.io/service_name")).andReturn("key");
        replayAll();

        Tag service = Tag.builder()
                .key("kubernetes.io/service_name")
                .value("service")
                .build();

        Tag anotherTag = Tag.builder().key("key").value("value").build();

        assertEquals(ImmutableMap.of("tag_key", "service"),
                tagExportConfig.tagLabels(ImmutableList.of(service, anotherTag), metricNameUtil));


        verifyAll();
    }

    @Test
    void getEnvTag() {
        TagExportConfig tagExportConfig = new TagExportConfig();
        tagExportConfig.setIncludeTags(ImmutableSet.of("asserts-env-name"));
        tagExportConfig.setEnvTags(ImmutableSet.of("asserts-env-name"));


        Tag envTag = Tag.builder()
                .key("asserts-env-name")
                .value("value")
                .build();
        assertEquals(Optional.of(envTag), tagExportConfig.getEnvTag(ImmutableList.of(envTag)));

        verifyAll();
    }
}
