/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.lambda;

import ai.asserts.aws.MetricNameUtil;
import ai.asserts.aws.cloudwatch.model.CWNamespace;
import ai.asserts.aws.cloudwatch.prometheus.GaugeExporter;
import ai.asserts.aws.resource.Resource;
import ai.asserts.aws.resource.ResourceMapper;
import com.google.common.annotations.VisibleForTesting;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.DestinationConfig;
import software.amazon.awssdk.services.lambda.model.FunctionConfiguration;
import software.amazon.awssdk.services.lambda.model.ListFunctionEventInvokeConfigsRequest;
import software.amazon.awssdk.services.lambda.model.ListFunctionEventInvokeConfigsResponse;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import static java.lang.String.format;

/**
 * Builds a lambda function given the function configuration and function resource and emits metrics for each
 * invoke config on the function or any of its aliases or versions
 */
@Component
@AllArgsConstructor
@Slf4j
public class LambdaFunctionBuilder {
    private final MetricNameUtil metricNameUtil;
    private final GaugeExporter gaugeExporter;
    private final ResourceMapper resourceMapper;

    public LambdaFunction buildFunction(String region,
                                        LambdaClient lambdaClient, FunctionConfiguration functionConfiguration,
                                        Optional<Resource> fnResource) {
        LambdaFunction lambdaFunction = LambdaFunction.builder()
                .region(region)
                .name(functionConfiguration.functionName())
                .arn(functionConfiguration.functionArn())
                .resource(fnResource.orElse(null))
                .memoryMB(functionConfiguration.memorySize())
                .timeoutSeconds(functionConfiguration.timeout())
                .build();

        try {
            ListFunctionEventInvokeConfigsRequest request = ListFunctionEventInvokeConfigsRequest.builder()
                    .functionName(functionConfiguration.functionName())
                    .build();
            ListFunctionEventInvokeConfigsResponse response = lambdaClient.listFunctionEventInvokeConfigs(request);
            if (response.hasFunctionEventInvokeConfigs() && response.functionEventInvokeConfigs().size() > 0) {
                log.info("Function {} has invoke configs", functionConfiguration.functionName());
                String metricPrefix = metricNameUtil.getMetricPrefix(CWNamespace.lambda.getNamespace());
                String metricName = format("%s_invoke_config", metricPrefix);
                Instant now = now();
                response.functionEventInvokeConfigs().forEach(config -> {
                    Map<String, String> labels = new TreeMap<>();
                    labels.put("region", region);
                    labels.put("function_name", functionConfiguration.functionName());
                    fnResource.ifPresent(fr -> fr.addTagLabels(labels, metricNameUtil));

                    DestinationConfig destConfig = config.destinationConfig();

                    // Success
                    if (destConfig.onSuccess() != null && destConfig.onSuccess().destination() != null) {
                        String urn = destConfig.onSuccess().destination();
                        resourceMapper.map(urn).ifPresent(targetResource -> {
                            labels.put("on", "success");
                            targetResource.addLabels(labels, "dest");
                            gaugeExporter.exportMetric(metricName, "", labels, now, 1.0D);
                        });
                    }
                    if (destConfig.onFailure() != null && destConfig.onFailure().destination() != null) {
                        String urn = destConfig.onFailure().destination();
                        resourceMapper.map(urn).ifPresent(targetResource -> {
                            labels.put("on", "failure");
                            targetResource.addLabels(labels, "dest");
                            gaugeExporter.exportMetric(metricName, "", labels, now, 1.0D);
                        });
                    }
                });
            }
        } catch (Exception e) {
            log.error("Failed to get function invoke configs for function " + functionConfiguration.functionArn(), e);
        }
        return lambdaFunction;
    }

    @VisibleForTesting
    Instant now() {
        return Instant.now();
    }
}
