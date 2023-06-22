/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws;

import ai.asserts.aws.config.ScrapeConfig;
import ai.asserts.aws.config.TagExportConfig;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import org.easymock.EasyMockSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.resourcegroupstaggingapi.model.Tag;

import static org.easymock.EasyMock.expect;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class TagUtilTest extends EasyMockSupport {
    private TagUtil testClass;
    private ScrapeConfig scrapeConfig;
    private MetricNameUtil metricNameUtil;

    @BeforeEach
    void setup() {
        scrapeConfig = mock(ScrapeConfig.class);
        metricNameUtil = mock(MetricNameUtil.class);
        scrapeConfig = mock(ScrapeConfig.class);
        testClass = new TagUtil(metricNameUtil);
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

        expect(scrapeConfig.shouldExportTag("kubernetes.io/service_name", "service"))
                .andReturn(true);
        expect(scrapeConfig.shouldExportTag("key", "value"))
                .andReturn(false);

        replayAll();
        assertEquals(ImmutableMap.of("tag_key", "service"),
                testClass.tagLabels(scrapeConfig, ImmutableList.of(service, anotherTag)));

        verifyAll();
    }
}
