/*
 * Copyright © 2021
 * Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.cloudwatch.prometheus;

import io.prometheus.client.CollectorRegistry;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
@Getter
@AllArgsConstructor
@Slf4j
public class MetricCollectors {
    private final CollectorRegistry collectorRegistry;
    private final Map<String, GaugeCollector> collectorsByMetric = new ConcurrentHashMap<>();

    public GaugeCollector getGauge(String name, String help) {
        return collectorsByMetric.computeIfAbsent(name, k -> {
            log.info("Creating Gauge for metric {}", name);
            GaugeCollector gaugeCollector = new GaugeCollector(name, help);
            gaugeCollector.register(collectorRegistry);
            return gaugeCollector;
        });
    }
}
