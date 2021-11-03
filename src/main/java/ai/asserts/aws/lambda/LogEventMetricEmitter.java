/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.lambda;

import ai.asserts.aws.MetricNameUtil;
import ai.asserts.aws.cloudwatch.config.LogScrapeConfig;
import ai.asserts.aws.cloudwatch.config.NamespaceConfig;
import ai.asserts.aws.cloudwatch.metrics.MetricSampleBuilder;
import ai.asserts.aws.cloudwatch.prometheus.GaugeExporter;
import ai.asserts.aws.resource.Resource;
import ai.asserts.aws.resource.TagFilterResourceProvider;
import com.google.common.annotations.VisibleForTesting;
import io.prometheus.client.Collector;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.cloudwatchlogs.model.FilteredLogEvent;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Component
@Slf4j
@AllArgsConstructor
public class LogEventMetricEmitter {
    private final TagFilterResourceProvider tagFilterResourceProvider;
    private final MetricNameUtil metricNameUtil;
    private final MetricSampleBuilder sampleBuilder;

    public Optional<Collector.MetricFamilySamples.Sample> getSample(NamespaceConfig namespaceConfig,
                                                                    LambdaLogMetricScrapeTask.FunctionLogScrapeConfig functionLogScrapeConfig,
                                                                    FilteredLogEvent filteredLogEvent) {
        LambdaFunction lambdaFunction = functionLogScrapeConfig.getLambdaFunction();
        LogScrapeConfig logScrapeConfig = functionLogScrapeConfig.getLogScrapeConfig();
        Map<String, String> logLabels = logScrapeConfig.extractLabels(filteredLogEvent.message());
        Set<Resource> functionResources = tagFilterResourceProvider.getFilteredResources(lambdaFunction.getRegion(),
                namespaceConfig);
        if (logLabels.size() > 0) {
            logLabels.put("region", functionLogScrapeConfig.getLambdaFunction().getRegion());
            logLabels.put("d_function_name", lambdaFunction.getName());
            functionResources.stream()
                    .filter(resource -> resource.getArn().equals(lambdaFunction.getArn()))
                    .findFirst()
                    .ifPresent(resource -> resource.addTagLabels(logLabels, metricNameUtil));
            return Optional.of(sampleBuilder.buildSingleSample("aws_lambda_logs", logLabels, getNow(), 1.0D));
        }
        return Optional.empty();
    }

    @VisibleForTesting
    Instant getNow() {
        return Instant.now();
    }
}
