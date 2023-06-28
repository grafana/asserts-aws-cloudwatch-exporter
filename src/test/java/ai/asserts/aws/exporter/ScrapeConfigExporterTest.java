/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.exporter;

import ai.asserts.aws.EnvironmentConfig;
import ai.asserts.aws.ScrapeConfigProvider;
import ai.asserts.aws.account.AWSAccount;
import ai.asserts.aws.account.AccountProvider;
import ai.asserts.aws.config.NamespaceConfig;
import ai.asserts.aws.config.ScrapeConfig;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import io.prometheus.client.Collector;
import io.prometheus.client.CollectorRegistry;
import org.easymock.EasyMockSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static ai.asserts.aws.MetricNameUtil.SCRAPE_NAMESPACE_LABEL;
import static ai.asserts.aws.MetricNameUtil.TENANT;
import static ai.asserts.aws.model.CWNamespace.lambda;
import static org.easymock.EasyMock.expect;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class ScrapeConfigExporterTest extends EasyMockSupport {
    private EnvironmentConfig environmentConfig;
    private ScrapeConfigProvider scrapeConfigProvider;
    private ScrapeConfig scrapeConfig;
    private NamespaceConfig namespaceConfig;
    private MetricSampleBuilder metricSampleBuilder;
    private Collector.MetricFamilySamples.Sample sample;
    private Collector.MetricFamilySamples metricFamilySamples;
    private CollectorRegistry collectorRegistry;
    private AccountProvider accountProvider;
    private ScrapeConfigExporter testClass;

    @BeforeEach
    public void setup() {
        environmentConfig = mock(EnvironmentConfig.class);
        scrapeConfigProvider = mock(ScrapeConfigProvider.class);
        scrapeConfig = mock(ScrapeConfig.class);
        namespaceConfig = mock(NamespaceConfig.class);
        namespaceConfig = mock(NamespaceConfig.class);
        metricSampleBuilder = mock(MetricSampleBuilder.class);
        sample = mock(Collector.MetricFamilySamples.Sample.class);
        metricFamilySamples = mock(Collector.MetricFamilySamples.class);
        collectorRegistry = mock(CollectorRegistry.class);
        accountProvider = mock(AccountProvider.class);
        testClass = new ScrapeConfigExporter(environmentConfig, accountProvider, scrapeConfigProvider,
                metricSampleBuilder,
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
        expect(environmentConfig.isProcessingOn()).andReturn(true);
        expect(accountProvider.getAccounts()).andReturn(ImmutableSet.of(
                AWSAccount.builder()
                        .tenant("tenant")
                        .build()
        ));
        expect(scrapeConfigProvider.getScrapeConfig("tenant")).andReturn(scrapeConfig);
        expect(scrapeConfig.getNamespaces()).andReturn(ImmutableList.of(namespaceConfig));
        expect(namespaceConfig.getName()).andReturn("ns1");
        expect(scrapeConfigProvider.getStandardNamespace("ns1")).andReturn(Optional.of(lambda));

        expect(namespaceConfig.getEffectiveScrapeInterval()).andReturn(61);
        expect(metricSampleBuilder.buildSingleSample("aws_exporter_scrape_interval",
                ImmutableMap.of(SCRAPE_NAMESPACE_LABEL, "AWS/Lambda", TENANT, "tenant"), 61.0D)).andReturn(
                Optional.of(sample));

        expect(metricSampleBuilder.buildFamily(ImmutableList.of(sample))).andReturn(Optional.of(metricFamilySamples));

        replayAll();
        assertEquals(ImmutableList.of(metricFamilySamples), testClass.collect());
        verifyAll();
    }

    @Test
    public void collect_off() {
        expect(environmentConfig.isProcessingOn()).andReturn(false);
        replayAll();
        assertEquals(ImmutableList.of(), testClass.collect());
        verifyAll();
    }
}
