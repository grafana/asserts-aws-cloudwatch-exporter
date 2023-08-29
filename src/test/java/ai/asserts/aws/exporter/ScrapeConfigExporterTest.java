/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.exporter;

import ai.asserts.aws.ScrapeConfigProvider;
import ai.asserts.aws.TaskExecutorUtil;
import ai.asserts.aws.TenantTask;
import ai.asserts.aws.account.AWSAccount;
import ai.asserts.aws.account.AccountProvider;
import ai.asserts.aws.config.NamespaceConfig;
import ai.asserts.aws.config.ScrapeConfig;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import io.prometheus.client.Collector.MetricFamilySamples.Sample;
import io.prometheus.client.CollectorRegistry;
import org.easymock.Capture;
import org.easymock.EasyMockSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.Future;
import java.util.function.Consumer;

import static ai.asserts.aws.MetricNameUtil.SCRAPE_NAMESPACE_LABEL;
import static ai.asserts.aws.model.CWNamespace.lambda;
import static org.easymock.EasyMock.capture;
import static org.easymock.EasyMock.eq;
import static org.easymock.EasyMock.expect;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class ScrapeConfigExporterTest extends EasyMockSupport {
    private ScrapeConfigProvider scrapeConfigProvider;
    private ScrapeConfig scrapeConfig;
    private NamespaceConfig namespaceConfig;
    private MetricSampleBuilder metricSampleBuilder;
    private Sample sample;
    private CollectorRegistry collectorRegistry;
    private AccountProvider accountProvider;
    private TaskExecutorUtil taskExecutorUtil;
    private Future<List<Sample>> mockFuture;
    private ScrapeConfigExporter testClass;

    @BeforeEach
    public void setup() {
        scrapeConfigProvider = mock(ScrapeConfigProvider.class);
        scrapeConfig = mock(ScrapeConfig.class);
        namespaceConfig = mock(NamespaceConfig.class);
        namespaceConfig = mock(NamespaceConfig.class);
        metricSampleBuilder = mock(MetricSampleBuilder.class);
        sample = mock(Sample.class);
        collectorRegistry = mock(CollectorRegistry.class);
        accountProvider = mock(AccountProvider.class);
        taskExecutorUtil = mock(TaskExecutorUtil.class);
        mockFuture = mock(Future.class);
        testClass = new ScrapeConfigExporter(accountProvider, scrapeConfigProvider, metricSampleBuilder,
                collectorRegistry, taskExecutorUtil);
    }

    @Test
    public void afterPropertiesSet() {
        collectorRegistry.register(testClass);
        replayAll();
        testClass.afterPropertiesSet();
        verifyAll();
    }

    @Test
    public void collect() throws Exception {
        AWSAccount awsAccount = AWSAccount.builder()
                .tenant("tenant")
                .build();
        expect(accountProvider.getAccounts()).andReturn(ImmutableSet.of(
                awsAccount
        ));
        Capture<TenantTask<List<Sample>>> c1 = Capture.newInstance();
        Capture<Consumer<List<Sample>>> c2 = Capture.newInstance();
        expect(taskExecutorUtil.executeAccountTask(eq(awsAccount), capture(c1))).andReturn(mockFuture);
        expect(scrapeConfigProvider.getScrapeConfig("tenant")).andReturn(scrapeConfig);
        expect(scrapeConfig.getNamespaces()).andReturn(ImmutableList.of(namespaceConfig));
        expect(namespaceConfig.getName()).andReturn("ns1");
        expect(scrapeConfigProvider.getStandardNamespace("ns1")).andReturn(Optional.of(lambda));

        expect(namespaceConfig.getEffectiveScrapeInterval()).andReturn(61);
        expect(metricSampleBuilder.buildSingleSample("aws_exporter_scrape_interval",
                ImmutableMap.of(SCRAPE_NAMESPACE_LABEL, "AWS/Lambda"), 61.0D)).andReturn(Optional.of(sample));

        taskExecutorUtil.awaitAll(eq(ImmutableList.of(mockFuture)), capture(c2));
        replayAll();

        testClass.collect();

        assertNotNull(c2.getValue());
        c2.getValue().accept(ImmutableList.of(sample));

        assertNotNull(c1.getValue());
        c1.getValue().call();


        verifyAll();
    }
}
