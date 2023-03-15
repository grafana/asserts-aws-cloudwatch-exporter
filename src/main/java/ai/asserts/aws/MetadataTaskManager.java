package ai.asserts.aws;

import ai.asserts.aws.exporter.ApiGatewayToLambdaBuilder;
import ai.asserts.aws.exporter.BasicMetricCollector;
import ai.asserts.aws.exporter.DynamoDBExporter;
import ai.asserts.aws.exporter.EC2ToEBSVolumeExporter;
import ai.asserts.aws.exporter.ECSServiceDiscoveryExporter;
import ai.asserts.aws.exporter.EMRExporter;
import ai.asserts.aws.exporter.KinesisAnalyticsExporter;
import ai.asserts.aws.exporter.KinesisFirehoseExporter;
import ai.asserts.aws.exporter.KinesisStreamExporter;
import ai.asserts.aws.exporter.LBToASGRelationBuilder;
import ai.asserts.aws.exporter.LambdaCapacityExporter;
import ai.asserts.aws.exporter.LambdaEventSourceExporter;
import ai.asserts.aws.exporter.LambdaInvokeConfigExporter;
import ai.asserts.aws.exporter.LambdaLogMetricScrapeTask;
import ai.asserts.aws.exporter.LoadBalancerExporter;
import ai.asserts.aws.exporter.RDSExporter;
import ai.asserts.aws.exporter.RedshiftExporter;
import ai.asserts.aws.exporter.ResourceRelationExporter;
import ai.asserts.aws.exporter.S3BucketExporter;
import ai.asserts.aws.exporter.SNSTopicExporter;
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
    private final KinesisStreamExporter kinesisStreamExporter;
    private final LoadBalancerExporter loadBalancerExporter;
    private final RDSExporter rdsExporter;
    private final DynamoDBExporter dynamoDBExporter;
    private final SNSTopicExporter snsTopicExporter;

    private final EMRExporter emrExporter;
    private final RateLimiter rateLimiter;

    @Getter
    private final List<LambdaLogMetricScrapeTask> logScrapeTasks = new ArrayList<>();

    public void afterPropertiesSet() {
        if (ecsServiceDiscoveryExporter.isPrimaryExporter()) {
            lambdaFunctionScraper.register(collectorRegistry);
            lambdaCapacityExporter.register(collectorRegistry);
            lambdaEventSourceExporter.register(collectorRegistry);
            lambdaInvokeConfigExporter.register(collectorRegistry);
            metricCollector.register(collectorRegistry);
            relationExporter.register(collectorRegistry);
            loadBalancerExporter.register(collectorRegistry);

            scrapeConfigProvider.getScrapeConfig().getLambdaConfig().ifPresent(nc -> {
                if (!CollectionUtils.isEmpty(nc.getLogs())) {
                    logScrapeTasks.add(lambdaLogScrapeTask);
                }
            });
        } else {
            log.info("Not primary exporter. Will skip scraping meta data information");
        }
    }

    @SuppressWarnings("unused")
    @Scheduled(fixedRateString = "${aws.metadata.scrape.manager.task.fixedDelay:300000}",
            initialDelayString = "${aws.metadata.scrape.manager.task.initialDelay:5000}")
    @Timed(description = "Time spent scraping AWS Resource meta data from all regions", histogram = true)
    public void updateMetadata() {
        if (!ecsServiceDiscoveryExporter.isPrimaryExporter()) {
            log.info("Not primary exporter. Skip meta data scraping.");
            return;
        }

        taskThreadPool.getExecutorService().submit(() -> rateLimiter.runTask(lambdaFunctionScraper::update));
        taskThreadPool.getExecutorService().submit(() -> rateLimiter.runTask(lambdaCapacityExporter::update));
        taskThreadPool.getExecutorService().submit(() -> rateLimiter.runTask(lambdaEventSourceExporter::update));
        taskThreadPool.getExecutorService().submit(() -> rateLimiter.runTask(lambdaInvokeConfigExporter::update));
        taskThreadPool.getExecutorService().submit(() -> rateLimiter.runTask(targetGroupLBMapProvider::update));
        taskThreadPool.getExecutorService().submit(() -> rateLimiter.runTask(lbToASGRelationBuilder::updateRouting));
        taskThreadPool.getExecutorService().submit(() -> rateLimiter.runTask(relationExporter::update));
        taskThreadPool.getExecutorService().submit(() -> rateLimiter.runTask(ec2ToEBSVolumeExporter::update));
        taskThreadPool.getExecutorService().submit(() -> rateLimiter.runTask(apiGatewayToLambdaBuilder::update));
        taskThreadPool.getExecutorService().submit(() -> rateLimiter.runTask(kinesisAnalyticsExporter::update));
        taskThreadPool.getExecutorService().submit(() -> rateLimiter.runTask(kinesisFirehoseExporter::update));
        taskThreadPool.getExecutorService().submit(() -> rateLimiter.runTask(s3BucketExporter::update));
        taskThreadPool.getExecutorService().submit(() -> rateLimiter.runTask(redshiftExporter::update));
        taskThreadPool.getExecutorService().submit(() -> rateLimiter.runTask(sqsQueueExporter::update));
        taskThreadPool.getExecutorService().submit(() -> rateLimiter.runTask(kinesisStreamExporter::update));
        taskThreadPool.getExecutorService().submit(() -> rateLimiter.runTask(loadBalancerExporter::update));
        taskThreadPool.getExecutorService().submit(() -> rateLimiter.runTask(rdsExporter::update));
        taskThreadPool.getExecutorService().submit(() -> rateLimiter.runTask(dynamoDBExporter::update));
        taskThreadPool.getExecutorService().submit(() -> rateLimiter.runTask(snsTopicExporter::update));
        taskThreadPool.getExecutorService().submit(() -> rateLimiter.runTask(() ->
                logScrapeTasks.forEach(LambdaLogMetricScrapeTask::update)));
        taskThreadPool.getExecutorService().submit(() -> rateLimiter.runTask(emrExporter::update));
    }

    @SuppressWarnings("unused")
    @Scheduled(fixedRateString = "${aws.metadata.scrape.manager.task.fixedDelay:300000}",
            initialDelayString = "${aws.metadata.scrape.manager.task.initialDelay:5000}")
    @Timed(description = "Time spent scraping AWS Resource meta data from all regions", histogram = true)
    public void perMinute() {
        taskThreadPool.getExecutorService().submit(() -> rateLimiter.runTask(scrapeConfigProvider::update));
        taskThreadPool.getExecutorService().submit(() -> rateLimiter.runTask(ecsServiceDiscoveryExporter));
    }
}
