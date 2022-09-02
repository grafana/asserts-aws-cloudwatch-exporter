/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.lambda;

import ai.asserts.aws.MetricNameUtil;
import ai.asserts.aws.config.LogScrapeConfig;
import ai.asserts.aws.config.NamespaceConfig;
import ai.asserts.aws.exporter.LambdaLogMetricScrapeTask.FunctionLogScrapeConfig;
import ai.asserts.aws.exporter.MetricSampleBuilder;
import ai.asserts.aws.resource.Resource;
import ai.asserts.aws.resource.ResourceTagHelper;
import io.prometheus.client.Collector;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.cloudwatchlogs.model.FilteredLogEvent;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static ai.asserts.aws.MetricNameUtil.SCRAPE_ACCOUNT_ID_LABEL;

@Component
@Slf4j
@AllArgsConstructor
public class LogEventMetricEmitter {
    private final ResourceTagHelper resourceTagHelper;
    private final MetricNameUtil metricNameUtil;
    private final MetricSampleBuilder sampleBuilder;

    public Optional<Collector.MetricFamilySamples.Sample> getSample(NamespaceConfig namespaceConfig,
                                                                    FunctionLogScrapeConfig config,
                                                                    FilteredLogEvent filteredLogEvent) {
        LambdaFunction lambdaFunction = config.getLambdaFunction();
        LogScrapeConfig logScrapeConfig = config.getLogScrapeConfig();
        Map<String, String> logLabels = logScrapeConfig.extractLabels(filteredLogEvent.message());

        Set<Resource> functionResources = resourceTagHelper.getFilteredResources(config.getAccount(),
                lambdaFunction.getRegion(), namespaceConfig);
        if (logLabels.size() > 0) {
            logLabels.put(SCRAPE_ACCOUNT_ID_LABEL, lambdaFunction.getAccount());
            logLabels.put("region", config.getLambdaFunction().getRegion());
            logLabels.put("d_function_name", lambdaFunction.getName());
            functionResources.stream()
                    .filter(resource -> resource.getArn().equals(lambdaFunction.getArn()))
                    .findFirst()
                    .ifPresent(resource -> resource.addEnvLabel(logLabels, metricNameUtil));
            return sampleBuilder.buildSingleSample("aws_lambda_logs", logLabels, 1.0D);
        }
        return Optional.empty();
    }
}
