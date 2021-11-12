/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.lambda;

import ai.asserts.aws.MetricNameUtil;
import com.google.common.collect.ImmutableSet;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.cloudwatch.model.Dimension;

import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static java.lang.String.format;

@Component
@AllArgsConstructor
public class LambdaLabelConverter {
    private final MetricNameUtil metricNameUtil;
    private static final Set<String> LAMBDA_NAMESPACES = ImmutableSet.of("AWS/Lambda", "LambdaInsights");

    public boolean shouldUseForNamespace(String namespace) {
        return LAMBDA_NAMESPACES.contains(namespace);
    }

    public Map<String, String> convert(Dimension dimension) {
        Map<String, String> labels = new TreeMap<>();
        String name = dimension.name();
        String value = dimension.value();
        if (name.equals("Resource")) {
            if (value.contains(":")) {
                String[] parts = value.split(":");
                labels.put("d_resource", parts[0]);
                labels.put("d_executed_version", parts[1]);
            } else {
                labels.put("d_resource", value);
            }
        } else {
            labels.put(format("d_%s", metricNameUtil.toSnakeCase(name)), value);
        }
        return labels;
    }
}
