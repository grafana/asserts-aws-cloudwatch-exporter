/*
 * Copyright © 2021
 * Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.lambda;

import ai.asserts.aws.AWSClientProvider;
import ai.asserts.aws.MetricNameUtil;
import ai.asserts.aws.Resource;
import ai.asserts.aws.ResourceMapper;
import ai.asserts.aws.cloudwatch.config.ScrapeConfigProvider;
import ai.asserts.aws.cloudwatch.model.CWNamespace;
import ai.asserts.aws.cloudwatch.prometheus.GaugeExporter;
import com.google.common.annotations.VisibleForTesting;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.ListEventSourceMappingsResponse;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.TimerTask;
import java.util.TreeMap;

import static java.lang.String.format;

@Component
@AllArgsConstructor
@Slf4j
public class LambdaEventSourceExporter extends TimerTask {
    public static final String HELP_TEXT = "Metric with lambda event source information";
    private final ScrapeConfigProvider scrapeConfigProvider;
    private final AWSClientProvider awsClientProvider;
    private final MetricNameUtil metricNameUtil;
    private final GaugeExporter gaugeExporter;
    private final ResourceMapper resourceMapper;

    public void run() {
        scrapeConfigProvider.getScrapeConfig().getRegions().forEach(region -> {
            try {
                LambdaClient client = awsClientProvider.getLambdaClient(region);

                // Get all event source mappings
                log.info("Discovering Lambda event source mappings for region={}", region);
                ListEventSourceMappingsResponse mappings = client.listEventSourceMappings();
                Instant now = now();
                if (mappings.hasEventSourceMappings()) {
                    mappings.eventSourceMappings().forEach(mapping -> {
                        Optional<Resource> functionOpt = resourceMapper.map(mapping.functionArn());
                        functionOpt.ifPresent(function -> resourceMapper.map(mapping.eventSourceArn()).ifPresent(resource -> {
                            String metricPrefix = metricNameUtil.getMetricPrefix(CWNamespace.lambda.getNamespace());
                            String metricName = format("%s_event_source", metricPrefix);
                            Map<String, String> labels = new TreeMap<>();
                            labels.put("region", region);
                            labels.put("lambda_function", function.getName());
                            labels.put("event_source_type", resource.getType().name());
                            labels.put("event_source_name", resource.getName());
                            gaugeExporter.exportMetric(metricName, HELP_TEXT, labels, now, 1.0D);
                        }));
                    });
                }
            } catch (Exception e) {
                log.info("Failed to discover event source mappings", e);
            }
        });
    }

    @VisibleForTesting
    Instant now() {
        return Instant.now();
    }
}
