/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.exporter;

import ai.asserts.aws.MetricNameUtil;
import ai.asserts.aws.cloudwatch.config.ScrapeConfigProvider;
import ai.asserts.aws.cloudwatch.model.CWNamespace;
import com.google.common.annotations.VisibleForTesting;
import io.micrometer.core.instrument.util.StringUtils;
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest;
import io.opentelemetry.proto.common.v1.StringKeyValue;
import io.opentelemetry.proto.metrics.v1.DoubleSummary;
import io.opentelemetry.proto.metrics.v1.DoubleSummaryDataPoint;
import io.opentelemetry.proto.metrics.v1.Metric;
import io.opentelemetry.proto.metrics.v1.ResourceMetrics;
import io.opentelemetry.proto.resource.v1.Resource;
import io.prometheus.client.Collector;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;

import static ai.asserts.aws.MetricNameUtil.SCRAPE_NAMESPACE_LABEL;
import static java.lang.String.format;

@Component
@AllArgsConstructor
@Slf4j
public class OpenTelemetryMetricConverter {
    private final MetricNameUtil metricNameUtil;
    private final MetricSampleBuilder builder;
    private final ScrapeConfigProvider configProvider;
    private final BasicMetricCollector metricCollector;


    public List<Collector.MetricFamilySamples> buildSamplesFromOT(ExportMetricsServiceRequest request) {
        List<Collector.MetricFamilySamples> metricFamilySamples = new ArrayList<>();
        Map<String, List<Collector.MetricFamilySamples.Sample>> samples = new TreeMap<>();
        int count = request.getResourceMetricsCount();
        for (int i = 0; i < count; i++) {
            ResourceMetrics resourceMetrics = request.getResourceMetrics(i);
            SortedMap<String, String> labels = new TreeMap<>(
                    extractAttributes(resourceMetrics.getResource()));

            resourceMetrics.getInstrumentationLibraryMetricsList().stream()
                    .flatMap(entry -> entry.getMetricsList().stream())
                    .forEach(metric -> buildFromOTMetric(samples, labels, metric));
        }
        samples.forEach((key, value) -> {
            log.info("Gathered metrics for {}", key);
            metricFamilySamples.add(builder.buildFamily(value));
        });
        return metricFamilySamples;
    }

    @VisibleForTesting
    void buildFromOTMetric(Map<String, List<Collector.MetricFamilySamples.Sample>> samplesByMetric,
                           SortedMap<String, String> labels, Metric metric) {
        String namespace = namespaceFromOTMetricName(metric.getName());
        String metricName = metricNameFromOTMetricName(namespace, metric.getName());
        if (StringUtils.isNotEmpty(metricName)) {
            labels.put(SCRAPE_NAMESPACE_LABEL, namespace);
            if (metric.getDataCase() == Metric.DataCase.DOUBLE_SUMMARY) {
                DoubleSummary doubleSummary = metric.getDoubleSummary();
                List<Collector.MetricFamilySamples.Sample> sampleList =
                        fromOTDoubleSummary(labels, metricName, doubleSummary);
                sampleList.forEach(sample ->
                        samplesByMetric.computeIfAbsent(sample.name, k -> new ArrayList<>()).add(sample));
            } else {
                log.error("{} not handled", metric.getDataCase().name());
            }
        }
    }

    @VisibleForTesting
    List<Collector.MetricFamilySamples.Sample> fromOTDoubleSummary(SortedMap<String, String> baseLabels,
                                                                   String metricName,
                                                                   DoubleSummary doubleSummary) {
        List<Collector.MetricFamilySamples.Sample> samples = new ArrayList<>();
        SortedMap<String, String> labels = new TreeMap<>(baseLabels);
        doubleSummary.getDataPointsList().forEach(doubleSummaryDataPoint -> {
            List<StringKeyValue> labelsList = doubleSummaryDataPoint.getLabelsList();
            labelsList.forEach(keyValue ->
                    labels.put(metricNameUtil.toSnakeCase(keyValue.getKey()), keyValue.getValue()));
            labels.remove("aws_exporter_arn");
            labels.remove("metric_name");
            labels.remove("provider");

            Long instant = doubleSummaryDataPoint.getTimeUnixNano() / 1000;
            String _sum = format("%s_sum", metricName);
            String _count = format("%s_count", metricName);
            String _min = format("%s_min", metricName);
            String _max = format("%s_max", metricName);

            samples.add(builder.buildSingleSample(_sum, labels, doubleSummaryDataPoint.getSum(), instant));
            samples.add(builder.buildSingleSample(_count, labels, 1.0D * doubleSummaryDataPoint.getCount(), instant));

            List<DoubleSummaryDataPoint.ValueAtQuantile> list = doubleSummaryDataPoint.getQuantileValuesList();
            if (list.size() >= 2) {
                DoubleSummaryDataPoint.ValueAtQuantile min = list.get(0);
                DoubleSummaryDataPoint.ValueAtQuantile max = list.get(list.size() - 1);

                samples.add(builder.buildSingleSample(_min, labels, min.getValue(), instant));
                samples.add(builder.buildSingleSample(_max, labels, max.getValue(), instant));
            }
        });
        return samples;
    }

    @VisibleForTesting
    Map<String, String> extractAttributes(Resource resource) {
        SortedMap<String, String> labels = new TreeMap<>();
        resource.getAttributesList().forEach(attribute -> {
            String key = attribute.getKey();
            key = key.replace('.', '_').replace("cloud_", "");
            labels.put(key, attribute.getValue().getStringValue());
        });
        labels.remove("metric_name");
        labels.remove("aws_exporter_arn");
        labels.remove("provider");
        labels.remove("namespace");
        return labels;
    }

    @VisibleForTesting
    String namespaceFromOTMetricName(String metricName) {
        return metricName.substring(metricName.indexOf('/') + 1, metricName.lastIndexOf('/'));
    }

    @VisibleForTesting
    String metricNameFromOTMetricName(String namespace, String name) {
        Optional<CWNamespace> standardNamespace = configProvider.getStandardNamespace(namespace);
        String prefix = "";
        if (standardNamespace.isPresent()) {
            prefix = standardNamespace.get().getMetricPrefix();
        }
        return format("%s_%s", prefix, metricNameUtil.toSnakeCase(name.substring(name.lastIndexOf('/') + 1)));
    }
}
