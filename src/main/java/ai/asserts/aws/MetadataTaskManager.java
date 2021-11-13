/*
 * Copyright © 2021
 * Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws;

import ai.asserts.aws.exporter.BasicMetricCollector;
import ai.asserts.aws.exporter.LambdaCapacityExporter;
import ai.asserts.aws.exporter.LambdaEventSourceExporter;
import ai.asserts.aws.exporter.LambdaInvokeConfigExporter;
import ai.asserts.aws.exporter.ResourceTagExporter;
import io.micrometer.core.annotation.Timed;
import io.prometheus.client.CollectorRegistry;
import lombok.AllArgsConstructor;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@AllArgsConstructor
public class MetadataTaskManager implements InitializingBean {
    private final CollectorRegistry collectorRegistry;
    private final LambdaCapacityExporter lambdaCapacityExporter;
    private final LambdaEventSourceExporter lambdaEventSourceExporter;
    private final LambdaInvokeConfigExporter lambdaInvokeConfigExporter;
    private final BasicMetricCollector metricCollector;
    private final ResourceTagExporter resourceTagExporter;
    private final TaskThreadPool taskThreadPool;

    public void afterPropertiesSet() {
        lambdaCapacityExporter.register(collectorRegistry);
        lambdaEventSourceExporter.register(collectorRegistry);
        lambdaInvokeConfigExporter.register(collectorRegistry);
        resourceTagExporter.register(collectorRegistry);
        metricCollector.register(collectorRegistry);
    }

    @SuppressWarnings("unused")
    @Scheduled(fixedRateString = "${aws.metric.scrape.manager.task.fixedDelay:300000}",
            initialDelayString = "${aws.metric.scrape.manager.task.initialDelay:5000}")
    @Timed(description = "Time spent scraping AWS Resource meta data from all regions", histogram = true)
    public void updateMetadata() {
        taskThreadPool.getExecutorService().submit(lambdaCapacityExporter::update);
        taskThreadPool.getExecutorService().submit(lambdaEventSourceExporter::update);
        taskThreadPool.getExecutorService().submit(lambdaInvokeConfigExporter::update);
    }

}
