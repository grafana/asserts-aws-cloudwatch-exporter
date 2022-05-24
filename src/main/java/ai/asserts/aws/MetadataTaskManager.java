package ai.asserts.aws;

import ai.asserts.aws.config.ScrapeConfig;
import ai.asserts.aws.exporter.ApiGatewayToLambdaBuilder;
import ai.asserts.aws.exporter.BasicMetricCollector;
import ai.asserts.aws.exporter.EC2ToEBSVolumeExporter;
import ai.asserts.aws.exporter.ECSServiceDiscoveryExporter;
import ai.asserts.aws.exporter.KinesisAnalyticsExporter;
import ai.asserts.aws.exporter.KinesisFirehoseExporter;
import ai.asserts.aws.exporter.LBToASGRelationBuilder;
import ai.asserts.aws.exporter.LambdaCapacityExporter;
import ai.asserts.aws.exporter.LambdaEventSourceExporter;
import ai.asserts.aws.exporter.LambdaInvokeConfigExporter;
import ai.asserts.aws.exporter.LambdaLogMetricScrapeTask;
import ai.asserts.aws.exporter.RedshiftExporter;
import ai.asserts.aws.exporter.ResourceExporter;
import ai.asserts.aws.exporter.ResourceRelationExporter;
import ai.asserts.aws.exporter.S3BucketExporter;
import ai.asserts.aws.exporter.SQSQueueExporter;
import ai.asserts.aws.exporter.TargetGroupLBMapProvider;
import ai.asserts.aws.lambda.LambdaFunctionScraper;
import io.micrometer.core.annotation.Timed;
import io.prometheus.client.CollectorRegistry;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.List;

@Component
@AllArgsConstructor
@Slf4j
public class MetadataTaskManager implements InitializingBean {
    private final CollectorRegistry collectorRegistry;
    private final LambdaFunctionScraper lambdaFunctionScraper;
    private final LambdaCapacityExporter lambdaCapacityExporter;
    private final LambdaEventSourceExporter lambdaEventSourceExporter;
    private final LambdaInvokeConfigExporter lambdaInvokeConfigExporter;
    private final LambdaLogMetricScrapeTask lambdaLogScrapeTask;
    private final BasicMetricCollector metricCollector;
    private final ResourceExporter resourceExporter;
    private final TargetGroupLBMapProvider targetGroupLBMapProvider;
    private final ResourceRelationExporter relationExporter;
    private final LBToASGRelationBuilder lbToASGRelationBuilder;
    private final EC2ToEBSVolumeExporter ec2ToEBSVolumeExporter;
    private final ApiGatewayToLambdaBuilder apiGatewayToLambdaBuilder;
    private final KinesisAnalyticsExporter kinesisAnalyticsExporter;
    private final KinesisFirehoseExporter kinesisFirehoseExporter;
    private final S3BucketExporter s3BucketExporter;
    private final TaskThreadPool taskThreadPool;
    private final ScrapeConfigProvider scrapeConfigProvider;
    private final ECSServiceDiscoveryExporter ecsServiceDiscoveryExporter;
    private final RedshiftExporter redshiftExporter;
    private final SQSQueueExporter sqsQueueExporter;

    @Getter
    private final List<LambdaLogMetricScrapeTask> logScrapeTasks = new ArrayList<>();

    public void afterPropertiesSet() {
        lambdaFunctionScraper.register(collectorRegistry);
        lambdaCapacityExporter.register(collectorRegistry);
        lambdaEventSourceExporter.register(collectorRegistry);
        lambdaInvokeConfigExporter.register(collectorRegistry);
        resourceExporter.register(collectorRegistry);
        metricCollector.register(collectorRegistry);
        relationExporter.register(collectorRegistry);
        ecsServiceDiscoveryExporter.register(collectorRegistry);
        sqsQueueExporter.register(collectorRegistry);

        ScrapeConfig scrapeConfig = scrapeConfigProvider.getScrapeConfig();
        scrapeConfig.getLambdaConfig().ifPresent(nc -> {
            if (!CollectionUtils.isEmpty(nc.getLogs())) {
                logScrapeTasks.add(lambdaLogScrapeTask);
            }
        });
    }

    @SuppressWarnings("unused")
    @Scheduled(fixedRateString = "${aws.metadata.scrape.manager.task.fixedDelay:300000}",
            initialDelayString = "${aws.metadata.scrape.manager.task.initialDelay:5000}")
    @Timed(description = "Time spent scraping AWS Resource meta data from all regions", histogram = true)
    public void updateMetadata() {
        taskThreadPool.getExecutorService().submit(lambdaFunctionScraper::update);
        taskThreadPool.getExecutorService().submit(lambdaCapacityExporter::update);
        taskThreadPool.getExecutorService().submit(lambdaEventSourceExporter::update);
        taskThreadPool.getExecutorService().submit(lambdaInvokeConfigExporter::update);
        taskThreadPool.getExecutorService().submit(resourceExporter::update);
        taskThreadPool.getExecutorService().submit(targetGroupLBMapProvider::update);
        taskThreadPool.getExecutorService().submit(lbToASGRelationBuilder::updateRouting);
        taskThreadPool.getExecutorService().submit(relationExporter::update);
        taskThreadPool.getExecutorService().submit(ec2ToEBSVolumeExporter::update);
        taskThreadPool.getExecutorService().submit(apiGatewayToLambdaBuilder::update);
        taskThreadPool.getExecutorService().submit(kinesisAnalyticsExporter::update);
        taskThreadPool.getExecutorService().submit(kinesisFirehoseExporter::update);
        taskThreadPool.getExecutorService().submit(s3BucketExporter::update);
        taskThreadPool.getExecutorService().submit(ecsServiceDiscoveryExporter::update);
        taskThreadPool.getExecutorService().submit(redshiftExporter::update);
        taskThreadPool.getExecutorService().submit(sqsQueueExporter::update);

        taskThreadPool.getExecutorService().submit(() ->
                logScrapeTasks.forEach(LambdaLogMetricScrapeTask::update));
        scrapeConfigProvider.update();
    }
}
