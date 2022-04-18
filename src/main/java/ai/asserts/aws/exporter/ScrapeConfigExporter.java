/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.exporter;

import ai.asserts.aws.ScrapeConfigProvider;
import com.google.common.collect.ImmutableMap;
import io.prometheus.client.Collector;
import io.prometheus.client.CollectorRegistry;
import lombok.AllArgsConstructor;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

import static ai.asserts.aws.MetricNameUtil.SCRAPE_NAMESPACE_LABEL;

@AllArgsConstructor
@Component
public class ScrapeConfigExporter extends Collector implements InitializingBean {
    private final ScrapeConfigProvider scrapeConfigProvider;
    private final MetricSampleBuilder sampleBuilder;
    private final CollectorRegistry collectorRegistry;

    @Override
    public void afterPropertiesSet() {
        register(collectorRegistry);
    }

    @Override
    public List<MetricFamilySamples> collect() {
        List<MetricFamilySamples> metricFamilySamples = new ArrayList<>();
        List<MetricFamilySamples.Sample> intervalSamples = new ArrayList<>();
        scrapeConfigProvider.getScrapeConfig().getNamespaces().forEach(namespaceConfig ->
                scrapeConfigProvider.getStandardNamespace(namespaceConfig.getName())
                        .ifPresent(cwNamespace ->
                                intervalSamples.add(sampleBuilder.buildSingleSample(
                                        "aws_exporter_scrape_interval",
                                        ImmutableMap.of(SCRAPE_NAMESPACE_LABEL, cwNamespace.getNormalizedNamespace()),
                                        namespaceConfig.getEffectiveScrapeInterval() * 1.0D))));

        if (intervalSamples.size() > 0) {
            metricFamilySamples.add(sampleBuilder.buildFamily(intervalSamples));
        }
        return metricFamilySamples;
    }
}
