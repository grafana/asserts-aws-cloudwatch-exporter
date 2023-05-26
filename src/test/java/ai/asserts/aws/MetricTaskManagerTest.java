package ai.asserts.aws;

import ai.asserts.aws.cloudwatch.alarms.AlarmFetcher;
import ai.asserts.aws.cloudwatch.alarms.AlarmMetricExporter;
import ai.asserts.aws.config.ScrapeConfig;
import ai.asserts.aws.exporter.BasicMetricCollector;
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
    private AccountProvider accountProvider;
    private CollectorRegistry collectorRegistry;
    private MetricTaskManager testClass;
    private AutowireCapableBeanFactory beanFactory;
    private ScrapeConfigProvider scrapeConfigProvider;
    private ScrapeConfig scrapeConfig;
    private MetricScrapeTask metricScrapeTask;
    private TaskThreadPool taskThreadPool;
    private ExecutorService executorService;
    private AlarmMetricExporter alarmMetricExporter;
    private AlarmFetcher alarmFetcher;
    private BasicMetricCollector metricCollector;

    private ECSServiceDiscoveryExporter ecsServiceDiscoveryExporter;

    @BeforeEach
    public void setup() {
        accountProvider = mock(AccountProvider.class);
        beanFactory = mock(AutowireCapableBeanFactory.class);
        scrapeConfigProvider = mock(ScrapeConfigProvider.class);
        scrapeConfig = mock(ScrapeConfig.class);
        metricScrapeTask = mock(MetricScrapeTask.class);
        collectorRegistry = mock(CollectorRegistry.class);
        taskThreadPool = mock(TaskThreadPool.class);
        executorService = mock(ExecutorService.class);
        alarmMetricExporter = mock(AlarmMetricExporter.class);
        alarmFetcher = mock(AlarmFetcher.class);
        ecsServiceDiscoveryExporter = mock(ECSServiceDiscoveryExporter.class);
        metricCollector = mock(BasicMetricCollector.class);
        replayAll();
        testClass = new MetricTaskManager(accountProvider, scrapeConfigProvider, collectorRegistry, beanFactory,
                taskThreadPool, alarmMetricExporter, alarmFetcher, ecsServiceDiscoveryExporter
                , new RateLimiter(metricCollector));
        verifyAll();
        resetAll();
    }

    @Test
    @SuppressWarnings("all")
    void afterPropertiesSet() {
        int delay = 60;
        expect(alarmMetricExporter.register(collectorRegistry)).andReturn(null);
        replayAll();
        testClass.afterPropertiesSet();
        verifyAll();
    }

    @Test
    void triggerScrapes_fetchMetricsTrue() {
        testClass = new MetricTaskManager(accountProvider, scrapeConfigProvider, collectorRegistry, beanFactory,
                taskThreadPool, alarmMetricExporter, alarmFetcher, ecsServiceDiscoveryExporter,
                new RateLimiter(metricCollector)) {
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
        expect(ecsServiceDiscoveryExporter.isPrimaryExporter()).andReturn(true);
        expect(scrapeConfigProvider.getScrapeConfig()).andReturn(scrapeConfig).anyTimes();
        expect(scrapeConfig.isFetchCWMetrics()).andReturn(true);
        expect(taskThreadPool.getExecutorService()).andReturn(executorService).anyTimes();

        expect(executorService.submit(capture(capture1))).andReturn(null);
        expect(executorService.submit(capture(capture2))).andReturn(null);
        expect(executorService.submit(capture(capture3))).andReturn(null);

        metricScrapeTask.update();
        expectLastCall().times(2);

        alarmFetcher.update();
        replayAll();
        testClass.triggerCWPullOperations();

        capture1.getValue().run();
        capture2.getValue().run();
        capture3.getValue().run();

        verifyAll();
    }

    @Test
    void triggerScrapes_fetchMetricsFalse() {
        testClass = new MetricTaskManager(accountProvider, scrapeConfigProvider, collectorRegistry, beanFactory,
                taskThreadPool, alarmMetricExporter, alarmFetcher, ecsServiceDiscoveryExporter,
                new RateLimiter(metricCollector)) {
            @Override
            void updateScrapeTasks() {
            }
        };
        testClass.getMetricScrapeTasks().put("account", ImmutableMap.of(
                "region1", ImmutableMap.of(300, metricScrapeTask),
                "region2", ImmutableMap.of(300, metricScrapeTask)
        ));

        Capture<Runnable> capture1 = newCapture();
        expect(ecsServiceDiscoveryExporter.isPrimaryExporter()).andReturn(true);
        expect(scrapeConfigProvider.getScrapeConfig()).andReturn(scrapeConfig).anyTimes();
        expect(scrapeConfig.isFetchCWMetrics()).andReturn(false);
        expect(taskThreadPool.getExecutorService()).andReturn(executorService).anyTimes();

        expect(executorService.submit(capture(capture1))).andReturn(null);

        alarmFetcher.update();
        replayAll();
        testClass.triggerCWPullOperations();

        capture1.getValue().run();

        verifyAll();
    }
}
