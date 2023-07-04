package ai.asserts.aws;

import ai.asserts.aws.account.AccountProvider;
import ai.asserts.aws.cloudwatch.alarms.AlarmFetcher;
import ai.asserts.aws.config.ScrapeConfig;
import ai.asserts.aws.exporter.ECSServiceDiscoveryExporter;
import ai.asserts.aws.exporter.MetricScrapeTask;
import com.google.common.collect.ImmutableMap;
import io.prometheus.client.CollectorRegistry;
import org.easymock.Capture;
import org.easymock.EasyMockSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;

import java.util.concurrent.ExecutorService;

import static org.easymock.EasyMock.capture;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.expectLastCall;
import static org.easymock.EasyMock.newCapture;

public class MetricTaskManagerTest extends EasyMockSupport {
    private EnvironmentConfig environmentConfig;
    private AccountProvider accountProvider;
    private CollectorRegistry collectorRegistry;
    private MetricTaskManager testClass;
    private AutowireCapableBeanFactory beanFactory;
    private ScrapeConfigProvider scrapeConfigProvider;
    private ScrapeConfig scrapeConfig;
    private MetricScrapeTask metricScrapeTask;
    private TaskThreadPool taskThreadPool;
    private ExecutorService executorService;
    private AlarmFetcher alarmFetcher;
    private ECSServiceDiscoveryExporter ecsServiceDiscoveryExporter;

    @BeforeEach
    public void setup() {
        environmentConfig = mock(EnvironmentConfig.class);
        accountProvider = mock(AccountProvider.class);
        beanFactory = mock(AutowireCapableBeanFactory.class);
        scrapeConfigProvider = mock(ScrapeConfigProvider.class);
        scrapeConfig = mock(ScrapeConfig.class);
        metricScrapeTask = mock(MetricScrapeTask.class);
        collectorRegistry = mock(CollectorRegistry.class);
        taskThreadPool = mock(TaskThreadPool.class);
        executorService = mock(ExecutorService.class);
        alarmFetcher = mock(AlarmFetcher.class);
        ecsServiceDiscoveryExporter = mock(ECSServiceDiscoveryExporter.class);
        environmentConfig = mock(EnvironmentConfig.class);
        replayAll();
        testClass = new MetricTaskManager(environmentConfig, accountProvider, scrapeConfigProvider, collectorRegistry,
                beanFactory,
                taskThreadPool, alarmFetcher, ecsServiceDiscoveryExporter);
        verifyAll();
        resetAll();
    }

    @Test
    void triggerScrapes_fetchMetricsTrue() {
        testClass = new MetricTaskManager(environmentConfig, accountProvider, scrapeConfigProvider, collectorRegistry,
                beanFactory,
                taskThreadPool, alarmFetcher, ecsServiceDiscoveryExporter) {
            @Override
            void updateScrapeTasks() {
            }
        };
        testClass.getMetricScrapeTasks().put("account", ImmutableMap.of(
                "region1", ImmutableMap.of(300, metricScrapeTask),
                "region2", ImmutableMap.of(300, metricScrapeTask)
        ));

        Capture<Runnable> capture1 = newCapture();
        Capture<Runnable> capture2 = newCapture();
        Capture<Runnable> capture3 = newCapture();
        expect(environmentConfig.isMultiTenant()).andReturn(false);
        expect(environmentConfig.isDistributed()).andReturn(false);
        expect(ecsServiceDiscoveryExporter.isPrimaryExporter()).andReturn(true);
        expect(scrapeConfigProvider.getScrapeConfig("")).andReturn(scrapeConfig).anyTimes();
        expect(scrapeConfig.isFetchCWMetrics()).andReturn(true).anyTimes();
        expect(taskThreadPool.getExecutorService()).andReturn(executorService).anyTimes();

        expect(executorService.submit(capture(capture1))).andReturn(null);
        expect(executorService.submit(capture(capture2))).andReturn(null);
        expect(executorService.submit(capture(capture3))).andReturn(null);

        metricScrapeTask.update();
        expectLastCall().times(2);

        alarmFetcher.update();
        expect(environmentConfig.isDisabled()).andReturn(false);
        replayAll();
        testClass.triggerCWPullOperations();

        capture1.getValue().run();
        capture2.getValue().run();
        capture3.getValue().run();

        verifyAll();
    }

    @Test
    void triggerScrapes_fetchMetricsFalse() {
        testClass = new MetricTaskManager(environmentConfig, accountProvider, scrapeConfigProvider, collectorRegistry,
                beanFactory,
                taskThreadPool, alarmFetcher, ecsServiceDiscoveryExporter) {
            @Override
            void updateScrapeTasks() {
            }
        };
        testClass.getMetricScrapeTasks().put("account", ImmutableMap.of(
                "region1", ImmutableMap.of(300, metricScrapeTask),
                "region2", ImmutableMap.of(300, metricScrapeTask)
        ));

        Capture<Runnable> capture1 = newCapture();
        expect(environmentConfig.isMultiTenant()).andReturn(false);
        expect(environmentConfig.isDistributed()).andReturn(false);
        expect(ecsServiceDiscoveryExporter.isPrimaryExporter()).andReturn(true);
        expect(scrapeConfigProvider.getScrapeConfig("")).andReturn(scrapeConfig).anyTimes();
        expect(taskThreadPool.getExecutorService()).andReturn(executorService).anyTimes();

        expect(executorService.submit(capture(capture1))).andReturn(null).times(3);
        expect(environmentConfig.isDisabled()).andReturn(false);
        alarmFetcher.update();
        replayAll();

        testClass.triggerCWPullOperations();

        capture1.getValue().run();

        verifyAll();
    }

    @Test
    void triggerScrapes_notPrimaryExporter() {
        testClass = new MetricTaskManager(environmentConfig, accountProvider, scrapeConfigProvider, collectorRegistry,
                beanFactory,
                taskThreadPool, alarmFetcher, ecsServiceDiscoveryExporter) {
            @Override
            void updateScrapeTasks() {
            }
        };
        testClass.getMetricScrapeTasks().put("account", ImmutableMap.of(
                "region1", ImmutableMap.of(300, metricScrapeTask),
                "region2", ImmutableMap.of(300, metricScrapeTask)
        ));

        expect(environmentConfig.isMultiTenant()).andReturn(false);
        expect(environmentConfig.isDistributed()).andReturn(false);
        expect(ecsServiceDiscoveryExporter.isPrimaryExporter()).andReturn(false);
        expect(environmentConfig.isDisabled()).andReturn(false);
        replayAll();
        testClass.triggerCWPullOperations();
        verifyAll();
    }
}
