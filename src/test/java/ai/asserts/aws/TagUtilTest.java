/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws;

import ai.asserts.aws.config.ScrapeConfig;
import ai.asserts.aws.config.TagExportConfig;
import ai.asserts.aws.resource.Resource;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import org.easymock.EasyMockSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.resourcegroupstaggingapi.model.Tag;

import java.util.Optional;

import static org.easymock.EasyMock.expect;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class TagUtilTest extends EasyMockSupport {
    private TagUtil testClass;
    private ScrapeConfigProvider scrapeConfigProvider;
    private ScrapeConfig scrapeConfig;
    private MetricNameUtil metricNameUtil;
    private Resource resource;

    @BeforeEach
    void setup() {
        scrapeConfigProvider = mock(ScrapeConfigProvider.class);
        scrapeConfig = mock(ScrapeConfig.class);
        metricNameUtil = mock(MetricNameUtil.class);
        resource = mock(Resource.class);
        testClass = new TagUtil(scrapeConfigProvider, metricNameUtil);
    }

    @Test
    void tagLabels() {
        TagExportConfig tagExportConfig = new TagExportConfig();
        tagExportConfig.setIncludeTags(ImmutableSet.of("kubernetes.io/service_name"));
        expect(metricNameUtil.toSnakeCase("kubernetes.io/service_name")).andReturn("key");

        Tag service = Tag.builder()
                .key("kubernetes.io/service_name")
                .value("service")
                .build();

        Tag anotherTag = Tag.builder().key("key").value("value").build();


        expect(scrapeConfigProvider.getScrapeConfig()).andReturn(scrapeConfig);
        expect(scrapeConfig.shouldExportTag("kubernetes.io/service_name", "service"))
                .andReturn(true);
        expect(scrapeConfig.shouldExportTag("key", "value"))
                .andReturn(false);

        replayAll();
        assertEquals(ImmutableMap.of("tag_key", "service"),
                testClass.tagLabels(ImmutableList.of(service, anotherTag)));


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
        expect(scrapeConfigProvider.getScrapeConfig()).andReturn(scrapeConfig).anyTimes();
        expect(scrapeConfig.getTagExportConfig()).andReturn(tagExportConfig).anyTimes();
        expect(resource.getTags()).andReturn(ImmutableList.of(envTag)).anyTimes();
        resource.setEnvTag(Optional.of(envTag));

        replayAll();
        testClass.setEnvTag(resource);

        verifyAll();
    }
}
