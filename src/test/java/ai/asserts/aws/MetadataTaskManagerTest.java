/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws;

import ai.asserts.aws.exporter.BasicMetricCollector;
import ai.asserts.aws.exporter.LambdaCapacityExporter;
import ai.asserts.aws.exporter.LambdaEventSourceExporter;
import ai.asserts.aws.exporter.LambdaInvokeConfigExporter;
import ai.asserts.aws.exporter.ResourceTagExporter;
import io.prometheus.client.CollectorRegistry;
import org.easymock.Capture;
import org.easymock.EasyMockSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;

import static org.easymock.EasyMock.capture;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.newCapture;

public class MetadataTaskManagerTest extends EasyMockSupport {
    private CollectorRegistry collectorRegistry;
    private LambdaCapacityExporter lambdaCapacityExporter;
    private LambdaEventSourceExporter lambdaEventSourceExporter;
    private LambdaInvokeConfigExporter lambdaInvokeConfigExporter;
    private BasicMetricCollector metricCollector;
    private ResourceTagExporter resourceTagExporter;
    private TaskThreadPool taskThreadPool;
    private ExecutorService executorService;
    private MetadataTaskManager testClass;

    @BeforeEach
    public void setup() {
        collectorRegistry = mock(CollectorRegistry.class);
        lambdaCapacityExporter = mock(LambdaCapacityExporter.class);
        lambdaEventSourceExporter = mock(LambdaEventSourceExporter.class);
        lambdaInvokeConfigExporter = mock(LambdaInvokeConfigExporter.class);
        metricCollector = mock(BasicMetricCollector.class);
        resourceTagExporter = mock(ResourceTagExporter.class);
        taskThreadPool = mock(TaskThreadPool.class);
        executorService = mock(ExecutorService.class);
        testClass = new MetadataTaskManager(collectorRegistry, lambdaCapacityExporter, lambdaEventSourceExporter,
                lambdaInvokeConfigExporter, metricCollector, resourceTagExporter, taskThreadPool);
    }

    @Test
    public void afterPropertiesSet() {
        expect(lambdaCapacityExporter.register(collectorRegistry)).andReturn(null);
        expect(lambdaEventSourceExporter.register(collectorRegistry)).andReturn(null);
        expect(lambdaInvokeConfigExporter.register(collectorRegistry)).andReturn(null);
        expect(metricCollector.register(collectorRegistry)).andReturn(null);
        expect(resourceTagExporter.register(collectorRegistry)).andReturn(null);
        replayAll();
        testClass.afterPropertiesSet();
        verifyAll();
    }

    @Test
    public void updateMetadata() {
        expect(taskThreadPool.getExecutorService()).andReturn(executorService).anyTimes();
        Capture<Runnable> capture1 = newCapture();
        Capture<Runnable> capture2 = newCapture();
        Capture<Runnable> capture3 = newCapture();

        expect(executorService.submit(capture(capture1))).andReturn(null);
        expect(executorService.submit(capture(capture2))).andReturn(null);
        expect(executorService.submit(capture(capture3))).andReturn(null);

        lambdaCapacityExporter.update();
        lambdaEventSourceExporter.update();
        lambdaInvokeConfigExporter.update();

        replayAll();
        testClass.updateMetadata();

        capture1.getValue().run();
        capture2.getValue().run();
        capture3.getValue().run();

        verifyAll();
    }
}
