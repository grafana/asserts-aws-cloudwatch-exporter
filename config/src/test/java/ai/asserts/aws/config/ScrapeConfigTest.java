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

    @Test
    void getEntityLabels() {
        DimensionToLabel labelExportConfig = mock(DimensionToLabel.class);

        ScrapeConfig scrapeConfig = ScrapeConfig.builder()
                .dimensionToLabels(ImmutableList.of(labelExportConfig))
                .build();

        expect(labelExportConfig.getNamespace()).andReturn("AWS/S3").anyTimes();
        expect(labelExportConfig.getDimensionName()).andReturn("BucketName").anyTimes();
        expect(labelExportConfig.getMapToLabel()).andReturn("job").anyTimes();
        expect(labelExportConfig.getValue("TestBucket")).andReturn(Optional.of("TestBucket"));

        replayAll();
        Map<String, String> labels = scrapeConfig.getEntityLabels("AWS/S3", ImmutableMap.of(
                "namespace", "AWS/S3", "BucketName", "TestBucket"));
        assertEquals(ImmutableMap.of( "job", "TestBucket"), labels);
        verifyAll();
    }

    @Test
    void additionalLabels() {
        RelabelConfig relabelConfig = mock(RelabelConfig.class);

        Map<String, String> labels = new TreeMap<>(ImmutableMap.of(
                "label", "value"));

        ScrapeConfig scrapeConfig = ScrapeConfig.builder()
                .relabelConfigs(ImmutableList.of(relabelConfig))
                .build();

        expect(relabelConfig.actionReplace()).andReturn(true).anyTimes();
        expect(relabelConfig.addReplacements("metric", labels))
                .andReturn(ImmutableMap.of("label", "value", "label2", "value2"));

        replayAll();

        Map<String, String> finalLabels = scrapeConfig.additionalLabels("metric", labels);
        assertEquals(ImmutableMap.of(
                "label", "value",
                "label2", "value2"
        ), finalLabels);
        verifyAll();
    }

    @Test
    void keepMetric() {
        RelabelConfig relabelConfig = mock(RelabelConfig.class);

        Map<String, String> labels = new TreeMap<>(ImmutableMap.of(
                "label", "value"));

        ScrapeConfig scrapeConfig = ScrapeConfig.builder()
                .relabelConfigs(ImmutableList.of(relabelConfig))
                .build();

        expect(relabelConfig.dropMetric("metric", labels)).andReturn(false);
        replayAll();

        assertTrue(scrapeConfig.keepMetric("metric", labels));
        verifyAll();
    }

    @Test
    void keepMetric_False() {
        RelabelConfig relabelConfig = mock(RelabelConfig.class);

        Map<String, String> labels = new TreeMap<>(ImmutableMap.of(
                "label", "value"));

        ScrapeConfig scrapeConfig = ScrapeConfig.builder()
                .relabelConfigs(ImmutableList.of(relabelConfig))
                .build();

        expect(relabelConfig.dropMetric("metric", labels)).andReturn(true);
        replayAll();

        assertFalse(scrapeConfig.keepMetric("metric", labels));
        verifyAll();
    }
}
