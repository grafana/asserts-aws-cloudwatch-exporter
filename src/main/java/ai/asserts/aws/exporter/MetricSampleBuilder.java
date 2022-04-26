/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.exporter;

import ai.asserts.aws.MetricNameUtil;
import ai.asserts.aws.ScrapeConfigProvider;
import ai.asserts.aws.cloudwatch.query.MetricQuery;
import io.prometheus.client.Collector.MetricFamilySamples;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.cloudwatch.model.MetricDataResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static io.prometheus.client.Collector.Type.GAUGE;

@Component
@Slf4j
@AllArgsConstructor
public class MetricSampleBuilder {
    private final MetricNameUtil metricNameUtil;
    private final LabelBuilder labelBuilder;
    private final ScrapeConfigProvider scrapeConfigProvider;

    public List<MetricFamilySamples.Sample> buildSamples(String account, String region, MetricQuery metricQuery,
                                                         MetricDataResult metricDataResult) {
        List<MetricFamilySamples.Sample> samples = new ArrayList<>();
        String metricName = metricNameUtil.exportedMetricName(metricQuery.getMetric(), metricQuery.getMetricStat());
        if (metricDataResult.timestamps().size() > 0) {
            Map<String, String> labels = scrapeConfigProvider.getScrapeConfig()
                    .additionalLabels(metricName, labelBuilder.buildLabels(account, region, metricQuery));
            for (int i = 0; i < metricDataResult.timestamps().size(); i++) {
                MetricFamilySamples.Sample sample = new MetricFamilySamples.Sample(
                        metricName,
                        new ArrayList<>(labels.keySet()),
                        new ArrayList<>(labels.values()),
                        metricDataResult.values().get(i));
                samples.add(sample);
            }
        }
        return samples;
    }

    public MetricFamilySamples.Sample buildSingleSample(String metricName, Map<String, String> inputLabels,
                                                        Double metric) {
        Map<String, String> labels = scrapeConfigProvider.getScrapeConfig().additionalLabels(metricName, inputLabels);
        return new MetricFamilySamples.Sample(
                metricName,
                new ArrayList<>(labels.keySet()),
                new ArrayList<>(labels.values()),
                metric);
    }

    public MetricFamilySamples.Sample buildSingleSample(String metricName, Map<String, String> inputLabels,
                                                        Double metric, Long time) {
        Map<String, String> labels = scrapeConfigProvider.getScrapeConfig().additionalLabels(metricName, inputLabels);
        return new MetricFamilySamples.Sample(
                metricName,
                new ArrayList<>(labels.keySet()),
                new ArrayList<>(labels.values()),
                metric,
                time);
    }

    public MetricFamilySamples buildFamily(List<MetricFamilySamples.Sample> samples) {
        return new MetricFamilySamples(samples.get(0).name, GAUGE, "", samples);
    }
}
