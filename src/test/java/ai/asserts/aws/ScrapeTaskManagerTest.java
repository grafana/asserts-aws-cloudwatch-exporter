/*
 * Copyright © 2021
 * Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws;

import ai.asserts.aws.cloudwatch.config.LogScrapeConfig;
import ai.asserts.aws.cloudwatch.config.MetricConfig;
import ai.asserts.aws.cloudwatch.config.NamespaceConfig;
import ai.asserts.aws.cloudwatch.config.ScrapeConfig;
import ai.asserts.aws.cloudwatch.config.ScrapeConfigProvider;
import ai.asserts.aws.cloudwatch.model.CWNamespace;
import ai.asserts.aws.exporter.BasicMetricCollector;
import ai.asserts.aws.exporter.LambdaCapacityExporter;
import ai.asserts.aws.exporter.LambdaEventSourceExporter;
import ai.asserts.aws.exporter.LambdaInvokeConfigExporter;
import ai.asserts.aws.exporter.LambdaLogMetricScrapeTask;
import ai.asserts.aws.exporter.MetricScrapeTask;
import ai.asserts.aws.exporter.ResourceTagExporter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import io.prometheus.client.CollectorRegistry;
import org.easymock.EasyMockSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;

import java.util.Optional;

import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.expectLastCall;

public class ScrapeTaskManagerTest extends EasyMockSupport {
    private CollectorRegistry collectorRegistry;
    private LambdaCapacityExporter lambdaCapacityExporter;
    private ScrapeTaskManager testClass;
    private AutowireCapableBeanFactory beanFactory;
    private ScrapeConfigProvider scrapeConfigProvider;
    private NamespaceConfig namespaceConfig;
    private LambdaEventSourceExporter lambdaEventSourceExporter;
    private MetricScrapeTask metricScrapeTask;
    private LambdaLogMetricScrapeTask logMetricScrapeTask;
    private LambdaInvokeConfigExporter lambdaInvokeConfigExporter;
    private BasicMetricCollector metricCollector;
    private ResourceTagExporter resourceTagExporter;
    private ScrapeConfig scrapeConfig;

    @BeforeEach
    public void setup() {
        beanFactory = mock(AutowireCapableBeanFactory.class);
        scrapeConfigProvider = mock(ScrapeConfigProvider.class);
        lambdaEventSourceExporter = mock(LambdaEventSourceExporter.class);
        scrapeConfig = mock(ScrapeConfig.class);
        namespaceConfig = mock(NamespaceConfig.class);
        metricScrapeTask = mock(MetricScrapeTask.class);
        collectorRegistry = mock(CollectorRegistry.class);
        lambdaCapacityExporter = mock(LambdaCapacityExporter.class);
        logMetricScrapeTask = mock(LambdaLogMetricScrapeTask.class);
        lambdaInvokeConfigExporter = mock(LambdaInvokeConfigExporter.class);
        resourceTagExporter = mock(ResourceTagExporter.class);
        metricCollector = mock(BasicMetricCollector.class);

        replayAll();
        testClass = new ScrapeTaskManager(collectorRegistry, beanFactory, scrapeConfigProvider, lambdaCapacityExporter,
                lambdaEventSourceExporter, lambdaInvokeConfigExporter, metricCollector, resourceTagExporter) {
            @Override
            MetricScrapeTask newScrapeTask(String region, Integer interval, Integer delay) {
                return metricScrapeTask;
            }

            @Override
            LambdaLogMetricScrapeTask newLogScrapeTask(String region) {
                return logMetricScrapeTask;
            }
        };
        verifyAll();
        resetAll();
    }

    @Test
    @SuppressWarnings("all")
    void setupScrapeTasks() {
        int delay = 60;

        expect(scrapeConfigProvider.getScrapeConfig()).andReturn(scrapeConfig);
        expect(scrapeConfig.getLambdaConfig()).andReturn(Optional.of(namespaceConfig));
        expect(scrapeConfig.getDelay()).andReturn(delay).anyTimes();

        expect(scrapeConfig.getRegions()).andReturn(ImmutableSet.of("region1", "region2")).anyTimes();
        ImmutableList<LogScrapeConfig> logScrapeConfigs = ImmutableList.of(LogScrapeConfig.builder()
                .build());
        expect(scrapeConfig.getNamespaces()).andReturn(ImmutableList.of(NamespaceConfig.builder()
                .name(CWNamespace.sqs.name())
                .metrics(ImmutableList.of(
                        MetricConfig.builder()
                                .name("Metric1")
                                .scrapeInterval(120)
                                .build(),
                        MetricConfig.builder()
                                .name("Metric2")
                                .scrapeInterval(300)
                                .build()))
                .logs(logScrapeConfigs)
                .build()));
        expect(namespaceConfig.getLogs()).andReturn(logScrapeConfigs).anyTimes();


        beanFactory.autowireBean(metricScrapeTask);
        expectLastCall().times(4);
        expect(metricScrapeTask.register(collectorRegistry)).andReturn(null).times(4);

        beanFactory.autowireBean(logMetricScrapeTask);
        expect(logMetricScrapeTask.register(collectorRegistry)).andReturn(null);
        beanFactory.autowireBean(logMetricScrapeTask);
        expect(logMetricScrapeTask.register(collectorRegistry)).andReturn(null);

        expect(lambdaCapacityExporter.register(collectorRegistry)).andReturn(null);
        expect(lambdaEventSourceExporter.register(collectorRegistry)).andReturn(null);
        expect(lambdaInvokeConfigExporter.register(collectorRegistry)).andReturn(null);
        expect(metricCollector.register(collectorRegistry)).andReturn(null);
        expect(resourceTagExporter.register(collectorRegistry)).andReturn(null);

        replayAll();
        testClass.afterPropertiesSet();
        verifyAll();
    }

}
