/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.exporter;

import ai.asserts.aws.MetricNameUtil;
import ai.asserts.aws.cloudwatch.config.NamespaceConfig;
import ai.asserts.aws.cloudwatch.config.ScrapeConfig;
import ai.asserts.aws.cloudwatch.config.ScrapeConfigProvider;
import ai.asserts.aws.resource.Resource;
import ai.asserts.aws.resource.TagFilterResourceProvider;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import io.prometheus.client.Collector;
import io.prometheus.client.Collector.MetricFamilySamples.Sample;
import org.easymock.EasyMockSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.easymock.EasyMock.expect;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class ResourceTagExporterTest extends EasyMockSupport {
    private ScrapeConfigProvider scrapeConfigProvider;
    private ScrapeConfig scrapeConfig;
    private NamespaceConfig namespaceConfig;
    private TagFilterResourceProvider tagFilterResourceProvider;
    private Resource resource;
    private MetricNameUtil metricNameUtil;
    private MetricSampleBuilder metricSampleBuilder;
    private Sample sample;
    private Collector.MetricFamilySamples familySamples;
    private ResourceTagExporter testClass;

    @BeforeEach
    public void setup() {
        scrapeConfigProvider = mock(ScrapeConfigProvider.class);
        scrapeConfig = mock(ScrapeConfig.class);
        namespaceConfig = mock(NamespaceConfig.class);
        tagFilterResourceProvider = mock(TagFilterResourceProvider.class);
        resource = mock(Resource.class);
        metricNameUtil = mock(MetricNameUtil.class);
        metricSampleBuilder = mock(MetricSampleBuilder.class);
        sample = mock(Sample.class);
        familySamples = mock(Collector.MetricFamilySamples.class);
        testClass = new ResourceTagExporter(scrapeConfigProvider, tagFilterResourceProvider, metricNameUtil,
                metricSampleBuilder);
    }

    @Test
    void collect() {
        expect(scrapeConfigProvider.getScrapeConfig()).andReturn(scrapeConfig).anyTimes();
        expect(scrapeConfig.getRegions()).andReturn(ImmutableSet.of("region1", "region2")).anyTimes();
        expect(scrapeConfig.getNamespaces()).andReturn(ImmutableList.of(namespaceConfig, namespaceConfig));

        expect(tagFilterResourceProvider.getFilteredResources("region1", namespaceConfig))
                .andReturn(ImmutableSet.of(resource)).times(2);
        expect(tagFilterResourceProvider.getFilteredResources("region2", namespaceConfig))
                .andReturn(ImmutableSet.of(resource)).times(2);

        expect(resource.getRegion()).andReturn("region1");
        ImmutableMap<String, String> labels = ImmutableMap.of("region", "region1");
        resource.addLabels(labels, "resource");
        resource.addTagLabels(labels, metricNameUtil);
        expect(metricSampleBuilder.buildSingleSample("aws_resource_tags", labels, 1.0D))
                .andReturn(sample);

        expect(metricSampleBuilder.buildFamily(ImmutableList.of(sample))).andReturn(familySamples);
        replayAll();
        assertEquals(ImmutableList.of(familySamples), testClass.collect());
        verifyAll();
    }

    @Test
    void collect_Empty() {
        expect(scrapeConfigProvider.getScrapeConfig()).andReturn(scrapeConfig).anyTimes();
        expect(scrapeConfig.getRegions()).andReturn(ImmutableSet.of("region1", "region2")).anyTimes();
        expect(scrapeConfig.getNamespaces()).andReturn(ImmutableList.of(namespaceConfig, namespaceConfig));

        expect(tagFilterResourceProvider.getFilteredResources("region1", namespaceConfig))
                .andReturn(ImmutableSet.of()).times(2);
        expect(tagFilterResourceProvider.getFilteredResources("region2", namespaceConfig))
                .andReturn(ImmutableSet.of()).times(2);

        replayAll();
        assertEquals(ImmutableList.of(), testClass.collect());
        verifyAll();
    }
}
