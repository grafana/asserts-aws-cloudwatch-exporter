/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.exporter;

import ai.asserts.aws.config.NamespaceConfig;
import ai.asserts.aws.config.ScrapeConfig;
import ai.asserts.aws.ScrapeConfigProvider;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.prometheus.client.Collector;
import io.prometheus.client.CollectorRegistry;
import org.easymock.EasyMockSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static ai.asserts.aws.MetricNameUtil.SCRAPE_NAMESPACE_LABEL;
import static ai.asserts.aws.model.CWNamespace.lambda;
import static org.easymock.EasyMock.expect;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class ScrapeConfigExporterTest extends EasyMockSupport {
    private ScrapeConfigProvider scrapeConfigProvider;
    private ScrapeConfig scrapeConfig;
    private NamespaceConfig namespaceConfig;
    private MetricSampleBuilder metricSampleBuilder;
    private Collector.MetricFamilySamples.Sample sample;
    private Collector.MetricFamilySamples metricFamilySamples;
    private CollectorRegistry collectorRegistry;
    private ScrapeConfigExporter testClass;

    @BeforeEach
    public void setup() {
        scrapeConfigProvider = mock(ScrapeConfigProvider.class);
        scrapeConfig = mock(ScrapeConfig.class);
        namespaceConfig = mock(NamespaceConfig.class);
        namespaceConfig = mock(NamespaceConfig.class);
        metricSampleBuilder = mock(MetricSampleBuilder.class);
        sample = mock(Collector.MetricFamilySamples.Sample.class);
        metricFamilySamples = mock(Collector.MetricFamilySamples.class);
        collectorRegistry = mock(CollectorRegistry.class);
        testClass = new ScrapeConfigExporter(scrapeConfigProvider, metricSampleBuilder,
                collectorRegistry);
    }

    @Test
    public void afterPropertiesSet() {
        collectorRegistry.register(testClass);
        replayAll();
        testClass.afterPropertiesSet();
        verifyAll();
    }

    @Test
    public void collect() {
        expect(scrapeConfigProvider.getScrapeConfig()).andReturn(scrapeConfig);
        expect(scrapeConfig.getNamespaces()).andReturn(ImmutableList.of(namespaceConfig));
        expect(namespaceConfig.getName()).andReturn("ns1");
        expect(scrapeConfigProvider.getStandardNamespace("ns1")).andReturn(Optional.of(lambda));

        expect(namespaceConfig.getEffectiveScrapeInterval()).andReturn(61);
        expect(metricSampleBuilder.buildSingleSample("aws_exporter_scrape_interval",
                ImmutableMap.of(SCRAPE_NAMESPACE_LABEL, "AWS/Lambda"), 61.0D)).andReturn(sample);

        expect(metricSampleBuilder.buildFamily(ImmutableList.of(sample))).andReturn(metricFamilySamples);

        replayAll();
        assertEquals(ImmutableList.of(metricFamilySamples), testClass.collect());
        verifyAll();
    }
}
