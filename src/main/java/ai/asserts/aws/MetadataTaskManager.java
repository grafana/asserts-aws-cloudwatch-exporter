
package ai.asserts.aws;

import ai.asserts.aws.cloudwatch.config.ScrapeConfig;
import ai.asserts.aws.cloudwatch.config.ScrapeConfigProvider;
import ai.asserts.aws.exporter.BasicMetricCollector;
import ai.asserts.aws.exporter.LambdaCapacityExporter;
import ai.asserts.aws.exporter.LambdaEventSourceExporter;
import ai.asserts.aws.exporter.LambdaInvokeConfigExporter;
import ai.asserts.aws.exporter.LambdaLogMetricScrapeTask;
import ai.asserts.aws.exporter.ResourceExporter;
import ai.asserts.aws.exporter.ResourceRelationExporter;
import ai.asserts.aws.exporter.ResourceTagExporter;
import ai.asserts.aws.exporter.TargetGroupLBMapProvider;
import com.google.common.annotations.VisibleForTesting;
import io.micrometer.core.annotation.Timed;
import io.prometheus.client.CollectorRegistry;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.List;

@Component
@AllArgsConstructor
@Slf4j
public class MetadataTaskManager implements InitializingBean {
    private final AutowireCapableBeanFactory beanFactory;
    private final CollectorRegistry collectorRegistry;
    private final LambdaCapacityExporter lambdaCapacityExporter;
    private final LambdaEventSourceExporter lambdaEventSourceExporter;
    private final LambdaInvokeConfigExporter lambdaInvokeConfigExporter;
    private final BasicMetricCollector metricCollector;
    private final ResourceExporter resourceExporter;
    private final ResourceTagExporter resourceTagExporter;
    private final TargetGroupLBMapProvider targetGroupLBMapProvider;
    private final ResourceRelationExporter relationExporter;
    private final TaskThreadPool taskThreadPool;
    private final ScrapeConfigProvider scrapeConfigProvider;

    @Getter
    private final List<LambdaLogMetricScrapeTask> logScrapeTasks = new ArrayList<>();

    public void afterPropertiesSet() {
        lambdaCapacityExporter.register(collectorRegistry);
        lambdaEventSourceExporter.register(collectorRegistry);
        lambdaInvokeConfigExporter.register(collectorRegistry);
        resourceExporter.register(collectorRegistry);
        resourceTagExporter.register(collectorRegistry);
        metricCollector.register(collectorRegistry);
        relationExporter.register(collectorRegistry);

        ScrapeConfig scrapeConfig = scrapeConfigProvider.getScrapeConfig();
        scrapeConfig.getLambdaConfig().ifPresent(nc -> {
            if (!CollectionUtils.isEmpty(nc.getLogs())) {
                scrapeConfig.getRegions().forEach(region ->
                        logScrapeTasks.add(lambdaLogScrapeTask(nc, region))
                );
            }
        });
    }

    @SuppressWarnings("unused")
    @Scheduled(fixedRateString = "${aws.metadata.scrape.manager.task.fixedDelay:300000}",
            initialDelayString = "${aws.metadata.scrape.manager.task.initialDelay:5000}")
    @Timed(description = "Time spent scraping AWS Resource meta data from all regions", histogram = true)
    public void updateMetadata() {
        taskThreadPool.getExecutorService().submit(lambdaCapacityExporter::update);
        taskThreadPool.getExecutorService().submit(lambdaEventSourceExporter::update);
        taskThreadPool.getExecutorService().submit(lambdaInvokeConfigExporter::update);
        taskThreadPool.getExecutorService().submit(resourceExporter::update);
        taskThreadPool.getExecutorService().submit(targetGroupLBMapProvider::update);
        taskThreadPool.getExecutorService().submit(relationExporter::update);

        taskThreadPool.getExecutorService().submit(() ->
                logScrapeTasks.forEach(LambdaLogMetricScrapeTask::update));
        scrapeConfigProvider.update();
    }

    @VisibleForTesting
    LambdaLogMetricScrapeTask newLogScrapeTask(String region) {
        return new LambdaLogMetricScrapeTask(region);
    }

    private LambdaLogMetricScrapeTask lambdaLogScrapeTask(ai.asserts.aws.cloudwatch.config.NamespaceConfig nc,
                                                          String region) {
        log.info("Setup lambda log scrape task for region {} with scrape configs {}", region, nc.getLogs());
        LambdaLogMetricScrapeTask logScraperTask = newLogScrapeTask(region);
        beanFactory.autowireBean(logScraperTask);
        logScraperTask.register(collectorRegistry);
        return logScraperTask;
    }
}
