package ai.asserts.aws;

import ai.asserts.aws.cloudwatch.alarms.AlarmFetcher;
import ai.asserts.aws.cloudwatch.alarms.AlarmMetricExporter;
import ai.asserts.aws.config.LogScrapeConfig;
import ai.asserts.aws.config.MetricConfig;
import ai.asserts.aws.config.NamespaceConfig;
import ai.asserts.aws.config.ScrapeConfig;
import ai.asserts.aws.model.CWNamespace;
import ai.asserts.aws.exporter.ECSServiceDiscoveryExporter;
import ai.asserts.aws.exporter.MetricScrapeTask;
import com.google.common.collect.ImmutableList;
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
        testClass = new MetricTaskManager(collectorRegistry, beanFactory, scrapeConfigProvider, ecsServiceDiscoveryExporter,
                taskThreadPool, alarmMetricExporter, alarmFetcher) {
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
    void afterPropertiesSet() {
        int delay = 60;

        expect(scrapeConfigProvider.getScrapeConfig()).andReturn(scrapeConfig);
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
        expect(alarmMetricExporter.register(collectorRegistry)).andReturn(null);
        alarmFetcher.sendAlarmsForRegions();
        replayAll();
        testClass.afterPropertiesSet();
        verifyAll();
    }

    @Test
    void triggerScrapes() {
        testClass.getMetricScrapeTasks().put(60, ImmutableMap.of("region1", metricScrapeTask));

        Capture<Runnable> capture1 = newCapture();

        expect(taskThreadPool.getExecutorService()).andReturn(executorService).anyTimes();
        metricScrapeTask.update();

        expect(executorService.submit(capture(capture1))).andReturn(null);

        expect(executorService.submit(ecsServiceDiscoveryExporter)).andReturn(null);


        replayAll();
        testClass.triggerScrapes();

        capture1.getValue().run();

        verifyAll();
    }
}
