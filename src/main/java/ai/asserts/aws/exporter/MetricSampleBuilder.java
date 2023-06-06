/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.exporter;

import ai.asserts.aws.MetricNameUtil;
import ai.asserts.aws.ScrapeConfigProvider;
import ai.asserts.aws.TaskExecutorUtil;
import ai.asserts.aws.cloudwatch.query.MetricQuery;
import ai.asserts.aws.config.ScrapeConfig;
import io.prometheus.client.Collector.MetricFamilySamples;
import io.prometheus.client.Collector.MetricFamilySamples.Sample;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.cloudwatch.model.MetricDataResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static io.prometheus.client.Collector.Type.GAUGE;

@Component
@Slf4j
@AllArgsConstructor
public class MetricSampleBuilder {
    private final MetricNameUtil metricNameUtil;
    private final LabelBuilder labelBuilder;
    private final ScrapeConfigProvider scrapeConfigProvider;

    private final TaskExecutorUtil taskExecutorUtil;

    public List<Sample> buildSamples(String account, String region, MetricQuery metricQuery,
                                     MetricDataResult metricDataResult) {
        List<Sample> samples = new ArrayList<>();
        String metricName = metricNameUtil.exportedMetricName(metricQuery.getMetric(), metricQuery.getMetricStat());
        if (metricDataResult.timestamps().size() > 0) {
            ScrapeConfig scrapeConfig = scrapeConfigProvider.getScrapeConfig();
            Map<String, String> labels = scrapeConfig
                    .additionalLabels(metricName, labelBuilder.buildLabels(account, region, metricQuery));
            labels.putIfAbsent("tenant", taskExecutorUtil.getTenant());
            labels.entrySet().removeIf(entry -> entry.getValue() == null);
            if (scrapeConfig.keepMetric(metricName, labels)) {
                for (int i = 0; i < metricDataResult.timestamps().size(); i++) {
                    Sample sample = new Sample(
                            metricName,
                            new ArrayList<>(labels.keySet()),
                            new ArrayList<>(labels.values()),
                            metricDataResult.values().get(i));
                    samples.add(sample);
                }
            }
        }
        return samples;
    }

    public Optional<Sample> buildSingleSample(String metricName, Map<String, String> inputLabels,
                                              Double metric) {
        ScrapeConfig scrapeConfig = scrapeConfigProvider.getScrapeConfig();
        Map<String, String> labels = scrapeConfig.additionalLabels(metricName, inputLabels);
        labels.putIfAbsent("tenant", taskExecutorUtil.getTenant());
        labels.entrySet().removeIf(entry -> entry.getValue() == null);
        if (scrapeConfig.keepMetric(metricName, inputLabels)) {
            return Optional.of(new Sample(
                    metricName,
                    new ArrayList<>(labels.keySet()),
                    new ArrayList<>(labels.values()),
                    metric));
        }
        return Optional.empty();
    }

    public Optional<MetricFamilySamples> buildFamily(List<Sample> samples) {
        if (samples.size() > 0) {
            return Optional.of(new MetricFamilySamples(samples.get(0).name, GAUGE, "", samples));
        }
        return Optional.empty();
    }
}
