package ai.asserts.aws;

import ai.asserts.aws.exporter.ApiGatewayToLambdaBuilder;
import ai.asserts.aws.exporter.BasicMetricCollector;
import ai.asserts.aws.exporter.DynamoDBExporter;
import ai.asserts.aws.exporter.EC2ToEBSVolumeExporter;
import ai.asserts.aws.exporter.ECSServiceDiscoveryExporter;
import ai.asserts.aws.exporter.ECSTaskProvider;
import ai.asserts.aws.exporter.EMRExporter;
import ai.asserts.aws.exporter.KinesisAnalyticsExporter;
import ai.asserts.aws.exporter.KinesisFirehoseExporter;
import ai.asserts.aws.exporter.KinesisStreamExporter;
import ai.asserts.aws.exporter.LBToASGRelationBuilder;
import ai.asserts.aws.exporter.LBToECSRoutingBuilder;
import ai.asserts.aws.exporter.LambdaCapacityExporter;
import ai.asserts.aws.exporter.LambdaEventSourceExporter;
import ai.asserts.aws.exporter.LambdaInvokeConfigExporter;
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
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class MetadataTaskManager implements InitializingBean {
    private final EnvironmentConfig environmentConfig;
    private final CollectorRegistry collectorRegistry;
    private final LambdaFunctionScraper lambdaFunctionScraper;
    private final LambdaCapacityExporter lambdaCapacityExporter;
    private final LambdaEventSourceExporter lambdaEventSourceExporter;
    private final LambdaInvokeConfigExporter lambdaInvokeConfigExporter;
    private final BasicMetricCollector metricCollector;
    private final TargetGroupLBMapProvider targetGroupLBMapProvider;
    private final ResourceRelationExporter relationExporter;
    private final LBToASGRelationBuilder lbToASGRelationBuilder;
    private final LBToECSRoutingBuilder lbToECSRoutingBuilder;
    private final EC2ToEBSVolumeExporter ec2ToEBSVolumeExporter;
    private final ApiGatewayToLambdaBuilder apiGatewayToLambdaBuilder;
    private final KinesisAnalyticsExporter kinesisAnalyticsExporter;
    private final KinesisFirehoseExporter kinesisFirehoseExporter;
    private final S3BucketExporter s3BucketExporter;
    private final TaskThreadPool taskThreadPool;
    private final ScrapeConfigProvider scrapeConfigProvider;
    private final ECSTaskProvider ecsTaskProvider;
    private final ECSServiceDiscoveryExporter ecsServiceDiscoveryExporter;
    private final RedshiftExporter redshiftExporter;
    private final SQSQueueExporter sqsQueueExporter;
    private final KinesisStreamExporter kinesisStreamExporter;
    private final LoadBalancerExporter loadBalancerExporter;
    private final RDSExporter rdsExporter;
    private final DynamoDBExporter dynamoDBExporter;
    private final SNSTopicExporter snsTopicExporter;
    private final EMRExporter emrExporter;

    public MetadataTaskManager(EnvironmentConfig environmentConfig, CollectorRegistry collectorRegistry,
                               LambdaFunctionScraper lambdaFunctionScraper,
                               LambdaCapacityExporter lambdaCapacityExporter,
                               LambdaEventSourceExporter lambdaEventSourceExporter,
                               LambdaInvokeConfigExporter lambdaInvokeConfigExporter,
                               BasicMetricCollector metricCollector,
                               TargetGroupLBMapProvider targetGroupLBMapProvider,
                               ResourceRelationExporter relationExporter,
                               LBToASGRelationBuilder lbToASGRelationBuilder,
                               LBToECSRoutingBuilder lbToECSRoutingBuilder,
                               EC2ToEBSVolumeExporter ec2ToEBSVolumeExporter,
                               ApiGatewayToLambdaBuilder apiGatewayToLambdaBuilder,
                               KinesisAnalyticsExporter kinesisAnalyticsExporter,
                               KinesisFirehoseExporter kinesisFirehoseExporter, S3BucketExporter s3BucketExporter,
                               @Qualifier("metadata-trigger-thread-pool") TaskThreadPool taskThreadPool,
                               ScrapeConfigProvider scrapeConfigProvider,
                               ECSTaskProvider ecsTaskProvider,
                               ECSServiceDiscoveryExporter ecsServiceDiscoveryExporter,
                               RedshiftExporter redshiftExporter, SQSQueueExporter sqsQueueExporter,
                               KinesisStreamExporter kinesisStreamExporter, LoadBalancerExporter loadBalancerExporter,
                               RDSExporter rdsExporter, DynamoDBExporter dynamoDBExporter,
                               SNSTopicExporter snsTopicExporter, EMRExporter emrExporter) {
        this.environmentConfig = environmentConfig;
        this.collectorRegistry = collectorRegistry;
        this.lambdaFunctionScraper = lambdaFunctionScraper;
        this.lambdaCapacityExporter = lambdaCapacityExporter;
        this.lambdaEventSourceExporter = lambdaEventSourceExporter;
        this.lambdaInvokeConfigExporter = lambdaInvokeConfigExporter;
        this.metricCollector = metricCollector;
        this.targetGroupLBMapProvider = targetGroupLBMapProvider;
        this.relationExporter = relationExporter;
        this.lbToASGRelationBuilder = lbToASGRelationBuilder;
        this.lbToECSRoutingBuilder = lbToECSRoutingBuilder;
        this.ec2ToEBSVolumeExporter = ec2ToEBSVolumeExporter;
        this.apiGatewayToLambdaBuilder = apiGatewayToLambdaBuilder;
        this.kinesisAnalyticsExporter = kinesisAnalyticsExporter;
        this.kinesisFirehoseExporter = kinesisFirehoseExporter;
        this.s3BucketExporter = s3BucketExporter;
        this.taskThreadPool = taskThreadPool;
        this.scrapeConfigProvider = scrapeConfigProvider;
        this.ecsTaskProvider = ecsTaskProvider;
        this.ecsServiceDiscoveryExporter = ecsServiceDiscoveryExporter;
        this.redshiftExporter = redshiftExporter;
        this.sqsQueueExporter = sqsQueueExporter;
        this.kinesisStreamExporter = kinesisStreamExporter;
        this.loadBalancerExporter = loadBalancerExporter;
        this.rdsExporter = rdsExporter;
        this.dynamoDBExporter = dynamoDBExporter;
        this.snsTopicExporter = snsTopicExporter;
        this.emrExporter = emrExporter;
    }

    public void afterPropertiesSet() {
        if (environmentConfig.isDisabled()) {
            log.info("All processing off");
            return;
        }

        if (environmentConfig.isMultiTenant() || environmentConfig.isDistributed() ||
                ecsServiceDiscoveryExporter.isPrimaryExporter()) {
            lambdaFunctionScraper.register(collectorRegistry);
            lambdaCapacityExporter.register(collectorRegistry);
            lambdaEventSourceExporter.register(collectorRegistry);
            lambdaInvokeConfigExporter.register(collectorRegistry);
            metricCollector.register(collectorRegistry);
            relationExporter.register(collectorRegistry);
            loadBalancerExporter.register(collectorRegistry);
        } else {
            log.info("Not primary exporter. Will skip scraping meta data information");
        }
    }

    @SuppressWarnings("unused")
    @Scheduled(fixedRateString = "${aws.metadata.scrape.manager.task.fixedDelay:300000}",
            initialDelayString = "${aws.metadata.scrape.manager.task.initialDelay:5000}")
    @Timed(description = "Time spent scraping AWS Resource meta data from all regions", histogram = true)
    public void updateMetadata() {
        if (environmentConfig.isDisabled()) {
            log.info("All processing off");
            return;
        }

        if (environmentConfig.isSingleTenant() && environmentConfig.isSingleInstance() &&
                !ecsServiceDiscoveryExporter.isPrimaryExporter()) {
            log.info("Not primary exporter. Skip meta data scraping.");
            return;
        }

        taskThreadPool.getExecutorService().submit(lambdaFunctionScraper::update);
        taskThreadPool.getExecutorService().submit(lambdaCapacityExporter::update);
        taskThreadPool.getExecutorService().submit(lambdaEventSourceExporter::update);
        taskThreadPool.getExecutorService().submit(lambdaInvokeConfigExporter::update);
        taskThreadPool.getExecutorService().submit(targetGroupLBMapProvider::update);
        taskThreadPool.getExecutorService().submit(lbToASGRelationBuilder::updateRouting);
        taskThreadPool.getExecutorService().submit(lbToECSRoutingBuilder);
        taskThreadPool.getExecutorService().submit(relationExporter::update);
        taskThreadPool.getExecutorService().submit(ec2ToEBSVolumeExporter::update);
        taskThreadPool.getExecutorService().submit(apiGatewayToLambdaBuilder::update);
        taskThreadPool.getExecutorService().submit(kinesisAnalyticsExporter::update);
        taskThreadPool.getExecutorService().submit(kinesisFirehoseExporter::update);
        taskThreadPool.getExecutorService().submit(s3BucketExporter::update);
        taskThreadPool.getExecutorService().submit(redshiftExporter::update);
        taskThreadPool.getExecutorService().submit(sqsQueueExporter::update);
        taskThreadPool.getExecutorService().submit(kinesisStreamExporter::update);
        taskThreadPool.getExecutorService().submit(loadBalancerExporter::update);
        taskThreadPool.getExecutorService().submit(rdsExporter::update);
        taskThreadPool.getExecutorService().submit(dynamoDBExporter::update);
        taskThreadPool.getExecutorService().submit(snsTopicExporter::update);
        taskThreadPool.getExecutorService().submit(emrExporter::update);
    }

    @SuppressWarnings("unused")
    @Scheduled(fixedRateString = "${aws.metadata.scrape.manager.task.fixedDelay:60000}",
            initialDelayString = "${aws.metadata.scrape.manager.task.initialDelay:5000}")
    @Timed(description = "Time spent scraping AWS Resource meta data from all regions", histogram = true)
    public void perMinute() {
        if (environmentConfig.isEnabled()) {
            taskThreadPool.getExecutorService().submit(scrapeConfigProvider::update);
            taskThreadPool.getExecutorService().submit(ecsTaskProvider);
            if (environmentConfig.isSingleTenant()) {
                taskThreadPool.getExecutorService().submit(ecsServiceDiscoveryExporter);
            }
        }
    }
}
