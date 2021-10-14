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
import ai.asserts.aws.lambda.LambdaEventSourceExporter;
import ai.asserts.aws.lambda.LambdaLogMetricScrapeTask;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import org.easymock.EasyMockSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;

import java.util.Timer;

import static org.easymock.EasyMock.anyLong;
import static org.easymock.EasyMock.eq;
import static org.easymock.EasyMock.expect;

public class ScrapeTaskManagerTest extends EasyMockSupport {
    private ScrapeTaskManager testClass;
    private AutowireCapableBeanFactory beanFactory;
    private ScrapeConfigProvider scrapeConfigProvider;
    private LambdaEventSourceExporter lambdaEventSourceExporter;
    private Timer timer;
    private ScrapeConfig scrapeConfig;

    @BeforeEach
    public void setup() {
        beanFactory = mock(AutowireCapableBeanFactory.class);
        scrapeConfigProvider = mock(ScrapeConfigProvider.class);
        lambdaEventSourceExporter = mock(LambdaEventSourceExporter.class);
        scrapeConfig = mock(ScrapeConfig.class);
        timer = mock(Timer.class);
        testClass = new ScrapeTaskManager(beanFactory, scrapeConfigProvider, lambdaEventSourceExporter) {
            @Override
            Timer getTimer() {
                return timer;
            }
        };
    }

    @Test
    void setupScrapeTasks() {
        int delay = 60;

        expect(scrapeConfigProvider.getScrapeConfig()).andReturn(scrapeConfig);
        expect(scrapeConfig.getDelay()).andReturn(delay).anyTimes();
        timer.schedule(eq(lambdaEventSourceExporter), anyLong(), eq(60_000L));

        expect(scrapeConfig.getRegions()).andReturn(ImmutableSet.of("region1", "region2")).anyTimes();
        ImmutableList<LogScrapeConfig> logScrapeConfigs = ImmutableList.of(LogScrapeConfig.builder()
                .build());
        expect(scrapeConfig.getNamespaces()).andReturn(ImmutableList.of(NamespaceConfig.builder()
                .name(CWNamespace.lambda.name())
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

        expectMetricScrapeTask("region1", 120, delay);
        expectMetricScrapeTask("region1", 300, delay);

        expectMetricScrapeTask("region2", 120, delay);
        expectMetricScrapeTask("region2", 300, delay);

        expectLambdaLogScrapeTask(logScrapeConfigs, "region1");
        expectLambdaLogScrapeTask(logScrapeConfigs, "region2");

        replayAll();
        testClass.setupScrapeTasks();
        verifyAll();
    }

    private void expectLambdaLogScrapeTask(ImmutableList<LogScrapeConfig> logScrapeConfigs, String region1) {
        LambdaLogMetricScrapeTask lambdaTask1 = new LambdaLogMetricScrapeTask(region1, logScrapeConfigs);
        beanFactory.autowireBean(lambdaTask1);
        timer.schedule(eq(lambdaTask1), anyLong(), eq(60_000L));
    }

    private void expectMetricScrapeTask(String region, int interval, int delay) {
        MetricScrapeTask task1 = new MetricScrapeTask(region, interval, delay);
        beanFactory.autowireBean(task1);
        timer.schedule(eq(task1), anyLong(), eq(interval * 1000L));
    }
}
