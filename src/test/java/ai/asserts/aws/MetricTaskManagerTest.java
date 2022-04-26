package ai.asserts.aws;

import ai.asserts.aws.AccountProvider.AWSAccount;
import ai.asserts.aws.cloudwatch.alarms.AlarmFetcher;
import ai.asserts.aws.cloudwatch.alarms.AlarmMetricExporter;
import ai.asserts.aws.config.NamespaceConfig;
import ai.asserts.aws.config.ScrapeConfig;
import ai.asserts.aws.exporter.ECSServiceDiscoveryExporter;
import ai.asserts.aws.exporter.MetricScrapeTask;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
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
    private AWSAccount awsAccount;
    private CollectorRegistry collectorRegistry;
    private MetricTaskManager testClass;
    private AutowireCapableBeanFactory beanFactory;
    private ScrapeConfigProvider scrapeConfigProvider;
    private NamespaceConfig namespaceConfig;
    private MetricScrapeTask metricScrapeTask;
    private ScrapeConfig scrapeConfig;
    private ECSServiceDiscoveryExporter ecsServiceDiscoveryExporter;
    private TaskThreadPool taskThreadPool;
    private ExecutorService executorService;
    private AlarmMetricExporter alarmMetricExporter;
    private AlarmFetcher alarmFetcher;

    @BeforeEach
    public void setup() {
        awsAccount = new AWSAccount("account", "role",
                ImmutableSet.of("region1", "region2"));
        accountProvider = mock(AccountProvider.class);
        beanFactory = mock(AutowireCapableBeanFactory.class);
        scrapeConfigProvider = mock(ScrapeConfigProvider.class);
        scrapeConfig = mock(ScrapeConfig.class);
        namespaceConfig = mock(NamespaceConfig.class);
        metricScrapeTask = mock(MetricScrapeTask.class);
        collectorRegistry = mock(CollectorRegistry.class);
        ecsServiceDiscoveryExporter = mock(ECSServiceDiscoveryExporter.class);
        taskThreadPool = mock(TaskThreadPool.class);
        executorService = mock(ExecutorService.class);
        alarmMetricExporter = mock(AlarmMetricExporter.class);
        alarmFetcher = mock(AlarmFetcher.class);

        replayAll();
        testClass = new MetricTaskManager(accountProvider, scrapeConfigProvider, collectorRegistry, beanFactory,
                ecsServiceDiscoveryExporter,
                taskThreadPool, alarmMetricExporter, alarmFetcher);
        verifyAll();
        resetAll();
    }

    @Test
    @SuppressWarnings("all")
    void afterPropertiesSet() {
        int delay = 60;
        expect(alarmMetricExporter.register(collectorRegistry)).andReturn(null);
        alarmFetcher.fetchAlarms();
        replayAll();
        testClass.afterPropertiesSet();
        verifyAll();
    }

    @Test
    void triggerScrapes() {
        testClass = new MetricTaskManager(accountProvider, scrapeConfigProvider, collectorRegistry, beanFactory,
                ecsServiceDiscoveryExporter,
                taskThreadPool, alarmMetricExporter, alarmFetcher) {
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

        expect(taskThreadPool.getExecutorService()).andReturn(executorService).anyTimes();

        expect(executorService.submit(capture(capture1))).andReturn(null);
        expect(executorService.submit(capture(capture2))).andReturn(null);

        expect(executorService.submit(ecsServiceDiscoveryExporter)).andReturn(null);

        metricScrapeTask.update();
        expectLastCall().times(2);

        replayAll();
        testClass.triggerScrapes();

        capture1.getValue().run();
        capture2.getValue().run();

        verifyAll();
    }
}
