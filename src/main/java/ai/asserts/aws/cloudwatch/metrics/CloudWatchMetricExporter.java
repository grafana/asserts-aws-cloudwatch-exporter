/*
 *  Copyright © 2022.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.cloudwatch.metrics;

import ai.asserts.aws.exporter.MetricSampleBuilder;
import io.prometheus.client.Collector;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class CloudWatchMetricExporter extends Collector {

    private final MetricSampleBuilder sampleBuilder;
    @Getter
    private List<Map<String, String>> metricLabels = Collections.synchronizedList(new ArrayList<>());

    public CloudWatchMetricExporter(MetricSampleBuilder sampleBuilder) {
        this.sampleBuilder = sampleBuilder;
    }

    public void addMetric(Map<String, String> labels) {
        metricLabels.add(labels);
    }

    @Override
    public List<MetricFamilySamples> collect() {
        List<MetricFamilySamples> latest = new ArrayList<>();
        if (metricLabels.size() > 0) {
            List<MetricFamilySamples.Sample> metrics1 = new ArrayList<>();
            metricLabels.forEach(labels -> {
                metrics1.add(sampleBuilder.buildSingleSample("aws_cloudwatch_metrics", labels,
                        1.0));
            });
            latest.add(sampleBuilder.buildFamily(metrics1));
        }
        return latest;
    }
}
