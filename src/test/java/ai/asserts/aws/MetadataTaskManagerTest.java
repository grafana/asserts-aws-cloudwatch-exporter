/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws;

import ai.asserts.aws.config.LogScrapeConfig;
import ai.asserts.aws.config.NamespaceConfig;
import ai.asserts.aws.config.ScrapeConfig;
import ai.asserts.aws.exporter.ApiGatewayToLambdaBuilder;
import ai.asserts.aws.exporter.BasicMetricCollector;
import ai.asserts.aws.exporter.EC2ToEBSVolumeExporter;
import ai.asserts.aws.exporter.ECSServiceDiscoveryExporter;
import ai.asserts.aws.exporter.KinesisAnalyticsExporter;
import ai.asserts.aws.exporter.KinesisFirehoseExporter;
import ai.asserts.aws.exporter.KinesisStreamExporter;
import ai.asserts.aws.exporter.LBToASGRelationBuilder;
import ai.asserts.aws.exporter.LambdaCapacityExporter;
import ai.asserts.aws.exporter.LambdaEventSourceExporter;
import ai.asserts.aws.exporter.LambdaInvokeConfigExporter;
import ai.asserts.aws.exporter.LambdaLogMetricScrapeTask;
import ai.asserts.aws.exporter.LoadBalancerExporter;
import ai.asserts.aws.exporter.RedshiftExporter;
import ai.asserts.aws.exporter.ResourceExporter;
import ai.asserts.aws.exporter.ResourceRelationExporter;
import ai.asserts.aws.exporter.S3BucketExporter;
import ai.asserts.aws.exporter.SQSQueueExporter;
import ai.asserts.aws.exporter.TargetGroupLBMapProvider;
import ai.asserts.aws.lambda.LambdaFunctionScraper;
import com.google.common.collect.ImmutableList;
import io.prometheus.client.CollectorRegistry;
import org.easymock.Capture;
import org.easymock.EasyMockSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.ExecutorService;

import static org.easymock.EasyMock.capture;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.newCapture;

public class MetadataTaskManagerTest extends EasyMockSupport {
    private CollectorRegistry collectorRegistry;
    private LambdaFunctionScraper lambdaFunctionScraper;
    private LambdaCapacityExporter lambdaCapacityExporter;
    private LambdaEventSourceExporter lambdaEventSourceExporter;
    private LambdaInvokeConfigExporter lambdaInvokeConfigExporter;
    private BasicMetricCollector metricCollector;
    private ResourceExporter resourceExporter;
    private TaskThreadPool taskThreadPool;
    private ExecutorService executorService;
    private ScrapeConfigProvider scrapeConfigProvider;
    private ScrapeConfig scrapeConfig;
    private NamespaceConfig namespaceConfig;
    private LogScrapeConfig logScrapeConfig;
    private LambdaLogMetricScrapeTask logMetricScrapeTask;
    private ResourceRelationExporter relationExporter;
    private TargetGroupLBMapProvider targetGroupLBMapProvider;
    private LBToASGRelationBuilder lbToASGRelationBuilder;
    private EC2ToEBSVolumeExporter ec2ToEBSVolumeExporter;
    private ApiGatewayToLambdaBuilder apiGatewayToLambdaBuilder;
    private KinesisAnalyticsExporter kinesisAnalyticsExporter;
    private KinesisFirehoseExporter kinesisFirehoseExporter;
    private S3BucketExporter s3BucketExporter;
    private ECSServiceDiscoveryExporter ecsServiceDiscoveryExporter;
    private RedshiftExporter redshiftExporter;
    private SQSQueueExporter sqsQueueExporter;
    private KinesisStreamExporter kinesisStreamExporter;
    private LoadBalancerExporter loadBalancerExporter;
    private MetadataTaskManager testClass;

    @BeforeEach
    public void setup() {
        collectorRegistry = mock(CollectorRegistry.class);
        lambdaFunctionScraper = mock(LambdaFunctionScraper.class);
        lambdaCapacityExporter = mock(LambdaCapacityExporter.class);
        lambdaEventSourceExporter = mock(LambdaEventSourceExporter.class);
        lambdaInvokeConfigExporter = mock(LambdaInvokeConfigExporter.class);
        metricCollector = mock(BasicMetricCollector.class);
        resourceExporter = mock(ResourceExporter.class);
        taskThreadPool = mock(TaskThreadPool.class);
        executorService = mock(ExecutorService.class);
        scrapeConfigProvider = mock(ScrapeConfigProvider.class);
        scrapeConfig = mock(ScrapeConfig.class);
        namespaceConfig = mock(NamespaceConfig.class);
        logScrapeConfig = mock(LogScrapeConfig.class);
        logMetricScrapeTask = mock(LambdaLogMetricScrapeTask.class);
        relationExporter = mock(ResourceRelationExporter.class);
        targetGroupLBMapProvider = mock(TargetGroupLBMapProvider.class);
        lbToASGRelationBuilder = mock(LBToASGRelationBuilder.class);
        ec2ToEBSVolumeExporter = mock(EC2ToEBSVolumeExporter.class);
        apiGatewayToLambdaBuilder = mock(ApiGatewayToLambdaBuilder.class);
        kinesisAnalyticsExporter = mock(KinesisAnalyticsExporter.class);
        kinesisFirehoseExporter = mock(KinesisFirehoseExporter.class);
        s3BucketExporter = mock(S3BucketExporter.class);
        ecsServiceDiscoveryExporter = mock(ECSServiceDiscoveryExporter.class);
        redshiftExporter = mock(RedshiftExporter.class);
        sqsQueueExporter = mock(SQSQueueExporter.class);
        kinesisStreamExporter = mock(KinesisStreamExporter.class);
        loadBalancerExporter = mock(LoadBalancerExporter.class);
        testClass = new MetadataTaskManager(
                collectorRegistry, lambdaFunctionScraper, lambdaCapacityExporter, lambdaEventSourceExporter,
                lambdaInvokeConfigExporter, logMetricScrapeTask, metricCollector, resourceExporter,
                targetGroupLBMapProvider, relationExporter, lbToASGRelationBuilder, ec2ToEBSVolumeExporter,
                apiGatewayToLambdaBuilder, kinesisAnalyticsExporter, kinesisFirehoseExporter,
                s3BucketExporter, taskThreadPool, scrapeConfigProvider, ecsServiceDiscoveryExporter, redshiftExporter,
                sqsQueueExporter, kinesisStreamExporter, loadBalancerExporter);
    }

    @Test
    public void afterPropertiesSet() {
        expect(lambdaFunctionScraper.register(collectorRegistry)).andReturn(null);
        expect(lambdaCapacityExporter.register(collectorRegistry)).andReturn(null);
        expect(lambdaEventSourceExporter.register(collectorRegistry)).andReturn(null);
        expect(lambdaInvokeConfigExporter.register(collectorRegistry)).andReturn(null);
        expect(metricCollector.register(collectorRegistry)).andReturn(null);
        expect(resourceExporter.register(collectorRegistry)).andReturn(null);
        expect(relationExporter.register(collectorRegistry)).andReturn(null);
        expect(ecsServiceDiscoveryExporter.register(collectorRegistry)).andReturn(null);
        expect(sqsQueueExporter.register(collectorRegistry)).andReturn(null);
        expect(loadBalancerExporter.register(collectorRegistry)).andReturn(null);
        expect(scrapeConfigProvider.getScrapeConfig()).andReturn(scrapeConfig);
        expect(scrapeConfig.getLambdaConfig()).andReturn(Optional.of(namespaceConfig));
        expect(namespaceConfig.getLogs()).andReturn(ImmutableList.of(logScrapeConfig)).anyTimes();
        replayAll();
        testClass.afterPropertiesSet();
        verifyAll();
    }

    @Test
    @SuppressWarnings("null")
    public void updateMetadata() {
        testClass.getLogScrapeTasks().add(logMetricScrapeTask);
        expect(taskThreadPool.getExecutorService()).andReturn(executorService).anyTimes();
        Capture<Runnable> capture0 = newCapture();
        Capture<Runnable> capture1 = newCapture();
        Capture<Runnable> capture2 = newCapture();
        Capture<Runnable> capture3 = newCapture();
        Capture<Runnable> capture4 = newCapture();
        Capture<Runnable> capture5 = newCapture();
        Capture<Runnable> capture6 = newCapture();
        Capture<Runnable> capture7 = newCapture();
        Capture<Runnable> capture8 = newCapture();
        Capture<Runnable> capture9 = newCapture();
        Capture<Runnable> capture10 = newCapture();
        Capture<Runnable> capture11 = newCapture();
        Capture<Runnable> capture12 = newCapture();
        Capture<Runnable> capture13 = newCapture();
        Capture<Runnable> capture14 = newCapture();
        Capture<Runnable> capture15 = newCapture();
        Capture<Runnable> capture16 = newCapture();
        Capture<Runnable> capture17 = newCapture();
        Capture<Runnable> capture18 = newCapture();

        expect(executorService.submit(capture(capture0))).andReturn(null);
        expect(executorService.submit(capture(capture1))).andReturn(null);
        expect(executorService.submit(capture(capture2))).andReturn(null);
        expect(executorService.submit(capture(capture3))).andReturn(null);
        expect(executorService.submit(capture(capture4))).andReturn(null);
        expect(executorService.submit(capture(capture5))).andReturn(null);
        expect(executorService.submit(capture(capture6))).andReturn(null);
        expect(executorService.submit(capture(capture7))).andReturn(null);
        expect(executorService.submit(capture(capture8))).andReturn(null);
        expect(executorService.submit(capture(capture9))).andReturn(null);
        expect(executorService.submit(capture(capture10))).andReturn(null);
        expect(executorService.submit(capture(capture11))).andReturn(null);
        expect(executorService.submit(capture(capture12))).andReturn(null);
        expect(executorService.submit(capture(capture13))).andReturn(null);
        expect(executorService.submit(capture(capture14))).andReturn(null);
        expect(executorService.submit(capture(capture15))).andReturn(null);
        expect(executorService.submit(capture(capture16))).andReturn(null);
        expect(executorService.submit(capture(capture17))).andReturn(null);
        expect(executorService.submit(capture(capture18))).andReturn(null);

        lambdaFunctionScraper.update();
        lambdaCapacityExporter.update();
        lambdaEventSourceExporter.update();
        lambdaInvokeConfigExporter.update();
        resourceExporter.update();
        logMetricScrapeTask.update();
        scrapeConfigProvider.update();
        targetGroupLBMapProvider.update();
        lbToASGRelationBuilder.updateRouting();
        relationExporter.update();
        ec2ToEBSVolumeExporter.update();
        apiGatewayToLambdaBuilder.update();
        kinesisFirehoseExporter.update();
        kinesisAnalyticsExporter.update();
        s3BucketExporter.update();
        ecsServiceDiscoveryExporter.update();
        redshiftExporter.update();
        sqsQueueExporter.update();
        kinesisStreamExporter.update();
        loadBalancerExporter.update();


        replayAll();
        testClass.updateMetadata();

        capture0.getValue().run();
        capture1.getValue().run();
        capture2.getValue().run();
        capture3.getValue().run();
        capture4.getValue().run();
        capture5.getValue().run();
        capture6.getValue().run();
        capture7.getValue().run();
        capture8.getValue().run();
        capture9.getValue().run();
        capture10.getValue().run();
        capture11.getValue().run();
        capture12.getValue().run();
        capture13.getValue().run();
        capture14.getValue().run();
        capture15.getValue().run();
        capture16.getValue().run();
        capture17.getValue().run();
        capture18.getValue().run();

        verifyAll();
    }
}
