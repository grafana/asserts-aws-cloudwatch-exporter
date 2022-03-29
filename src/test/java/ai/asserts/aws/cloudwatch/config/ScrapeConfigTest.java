/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.cloudwatch.config;

import ai.asserts.aws.resource.Resource;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.easymock.EasyMockSupport;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.ecs.model.ContainerDefinition;
import software.amazon.awssdk.services.ecs.model.TaskDefinition;
import software.amazon.awssdk.services.resourcegroupstaggingapi.model.Tag;

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
    public void getECSScrapeConfig_None() {
        ScrapeConfig scrapeConfig = ScrapeConfig.builder().build();
        assertEquals(Optional.empty(), scrapeConfig.getECSScrapeConfig(TaskDefinition.builder().build()));
    }

    @Test
    public void getECSScrapeConfig_Exists() {
        ECSTaskDefScrapeConfig mockConfig = mock(ECSTaskDefScrapeConfig.class);
        ScrapeConfig scrapeConfig = ScrapeConfig.builder()
                .ecsTaskScrapeConfigs(ImmutableList.of(mockConfig))
                .build();
        TaskDefinition taskDefinition = TaskDefinition.builder()
                .containerDefinitions(ContainerDefinition.builder().name("cn").build())
                .build();

        expect(mockConfig.getContainerDefinitionName()).andReturn("cn");

        replayAll();
        assertEquals(Optional.of(mockConfig), scrapeConfig.getECSScrapeConfig(taskDefinition));
        verifyAll();
    }

    @Test
    void shouldExportTag_DefaultNone() {
        ScrapeConfig scrapeConfig = ScrapeConfig.builder()
                .build();

        Tag tag = Tag.builder().build();

        replayAll();
        assertFalse(scrapeConfig.shouldExportTag(tag));
        verifyAll();
    }

    @Test
    void shouldExportTag() {
        TagExportConfig tagExportConfig = mock(TagExportConfig.class);
        ScrapeConfig scrapeConfig = ScrapeConfig.builder()
                .tagExportConfig(tagExportConfig)
                .build();

        Tag tag = Tag.builder().build();

        expect(tagExportConfig.shouldCaptureTag(tag)).andReturn(true);

        replayAll();
        assertTrue(scrapeConfig.shouldExportTag(tag));
        verifyAll();
    }

    @Test
    void setEnvTag() {
        TagExportConfig tagExportConfig = mock(TagExportConfig.class);
        Resource mockResource = mock(Resource.class);

        ScrapeConfig scrapeConfig = ScrapeConfig.builder()
                .tagExportConfig(tagExportConfig)
                .build();

        Tag envTag = Tag.builder().build();

        ImmutableList<Tag> tags = ImmutableList.of(envTag);
        expect(mockResource.getTags()).andReturn(tags);
        expect(tagExportConfig.getEnvTag(tags)).andReturn(Optional.of(envTag));

        mockResource.setEnvTag(Optional.of(envTag));

        replayAll();
        scrapeConfig.setEnvTag(mockResource);
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
        expect(labelExportConfig.getEntityType()).andReturn("Service").anyTimes();

        replayAll();
        Map<String, String> labels = scrapeConfig.getEntityLabels("AWS/S3", ImmutableMap.of(
                "namespace", "AWS/S3", "BucketName", "TestBucket"));
        assertEquals(ImmutableMap.of("asserts_entity_type", "Service", "job", "TestBucket"), labels);
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
}
