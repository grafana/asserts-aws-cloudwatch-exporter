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
import ai.asserts.aws.cloudwatch.metrics.MetricScrapeTask;
import ai.asserts.aws.cloudwatch.model.CWNamespace;
import ai.asserts.aws.lambda.LambdaCapacityExporter;
import ai.asserts.aws.lambda.LambdaEventSourceExporter;
import ai.asserts.aws.lambda.LambdaLogMetricScrapeTask;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import org.easymock.EasyMockSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;

import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.easymock.EasyMock.anyLong;
import static org.easymock.EasyMock.anyObject;
import static org.easymock.EasyMock.eq;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.expectLastCall;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class ScrapeTaskManagerTest extends EasyMockSupport {
    private ScrapeTaskManager testClass;
    private AutowireCapableBeanFactory beanFactory;
    private ScrapeConfigProvider scrapeConfigProvider;
    private NamespaceConfig namespaceConfig;
    private LambdaEventSourceExporter lambdaEventSourceExporter;
    private ScheduledExecutorService scheduledExecutorService;
    private MetricScrapeTask metricScrapeTask;
    private ScrapeConfig scrapeConfig;

    @BeforeEach
    public void setup() {
        beanFactory = mock(AutowireCapableBeanFactory.class);
        scrapeConfigProvider = mock(ScrapeConfigProvider.class);
        lambdaEventSourceExporter = mock(LambdaEventSourceExporter.class);
        scrapeConfig = mock(ScrapeConfig.class);
        namespaceConfig = mock(NamespaceConfig.class);
        scheduledExecutorService = mock(ScheduledExecutorService.class);
        metricScrapeTask = mock(MetricScrapeTask.class);

        expect(scrapeConfigProvider.getScrapeConfig()).andReturn(scrapeConfig);
        expect(scrapeConfig.getNumTaskThreads()).andReturn(5);
        replayAll();
        testClass = new ScrapeTaskManager(beanFactory, scrapeConfigProvider, lambdaEventSourceExporter) {
            @Override
            ScheduledExecutorService getExecutorService(int numThreads) {
                assertEquals(5, numThreads);
                return scheduledExecutorService;
            }

            @Override
            MetricScrapeTask newScrapeTask(String region, Integer interval, Integer delay) {
                return metricScrapeTask;
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
        expect(metricScrapeTask.register()).andReturn(null).times(4);

        expectLambdaLogScrapeTask(logScrapeConfigs, "region1");
        expectLambdaLogScrapeTask(logScrapeConfigs, "region2");

        expect(scheduledExecutorService.scheduleAtFixedRate(eq(lambdaEventSourceExporter), anyLong(),
                eq(60 * 1000L), eq(MILLISECONDS))).andReturn(null);

        beanFactory.autowireBean(anyObject(LambdaCapacityExporter.class));
        expect(scheduledExecutorService.scheduleAtFixedRate(anyObject(LambdaCapacityExporter.class), anyLong(),
                eq(60 * 1000L), eq(MILLISECONDS))).andReturn(null);

        replayAll();
        testClass.setupScrapeTasks();
        verifyAll();
    }

    private void expectLambdaLogScrapeTask(ImmutableList<LogScrapeConfig> logScrapeConfigs, String region1) {
        LambdaLogMetricScrapeTask lambdaTask1 = new LambdaLogMetricScrapeTask(region1, logScrapeConfigs);
        beanFactory.autowireBean(lambdaTask1);
        expect(scheduledExecutorService.scheduleAtFixedRate(eq(lambdaTask1), anyLong(),
                eq(60_000L), eq(MILLISECONDS))).andReturn(null);
    }

}
