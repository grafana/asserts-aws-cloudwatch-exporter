/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.exporter;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.util.concurrent.AtomicDouble;
import io.prometheus.client.Collector;
import io.prometheus.client.Collector.MetricFamilySamples.Sample;
import io.prometheus.client.Histogram;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Component
@Slf4j
public class BasicMetricCollector extends Collector {
    private Map<Key, Double> gaugeValues = new ConcurrentHashMap<>();
    private final Cache<Key, AtomicLong> counters;
    private final Cache<Key, LatencyCounter> latencyCounters;
    private final Cache<Key, Histogram> histograms;

    public BasicMetricCollector() {
        counters = CacheBuilder.newBuilder()
                .expireAfterAccess(10, TimeUnit.MINUTES)
                .build();

        latencyCounters = CacheBuilder.newBuilder()
                .expireAfterAccess(10, TimeUnit.MINUTES)
                .build();

        histograms = CacheBuilder.newBuilder()
                .expireAfterAccess(10, TimeUnit.MINUTES)
                .build();
    }

    @Override
    public List<MetricFamilySamples> collect() {
        List<MetricFamilySamples> familySamples = new ArrayList<>();

        try {
            Map<String, List<Sample>> gaugeSamples = new TreeMap<>();
            gaugeValues.forEach((key, value) ->
                    gaugeSamples.computeIfAbsent(key.metricName, k -> new ArrayList<>())
                            .add(new Sample(key.metricName, key.labelNames, key.labelValues, value)));
            gaugeSamples.forEach((name, samples) ->
                    familySamples.add(new MetricFamilySamples(name, Type.GAUGE, "", samples)));
            gaugeValues = new ConcurrentHashMap<>();

            Map<String, List<Sample>> counterSamples = new TreeMap<>();
            counters.asMap().forEach((key, value) ->
                    counterSamples.computeIfAbsent(key.metricName, k -> new ArrayList<>())
                            .add(new Sample(key.metricName, key.labelNames, key.labelValues, value.get())));
            counterSamples.forEach((name, samples) ->
                    familySamples.add(new MetricFamilySamples(name, Type.COUNTER, "", samples)));

            Map<String, List<Sample>> latencySamples = new TreeMap<>();
            latencyCounters.asMap().forEach((key, latencyCounter) -> {
                int count = latencyCounter.getCount();
                double value = latencyCounter.getValue();
                latencySamples.computeIfAbsent(key.metricName + "_count", k -> new ArrayList<>())
                        .add(new Sample(key.metricName + "_count", key.labelNames, key.labelValues, count));
                latencySamples.computeIfAbsent(key.metricName + "_sum", k -> new ArrayList<>())
                        .add(new Sample(key.metricName + "_sum", key.labelNames, key.labelValues, value));
            });

            histograms.asMap().values().forEach(histogram -> familySamples.addAll(histogram.collect()));

            latencySamples.forEach((name, samples) ->
                    familySamples.add(new MetricFamilySamples(name, Type.COUNTER, "", samples)));
        } catch (Exception e) {
            log.error("Failed to collect metric samples", e);
        }

        return familySamples;
    }

    public void recordGaugeValue(String metricName, SortedMap<String, String> inputLabels, Double value) {
        gaugeValues.put(Key.builder()
                .metricName(metricName)
                .labelNames(new ArrayList<>(inputLabels.keySet()))
                .labelValues(new ArrayList<>(inputLabels.values()))
                .build(), value);
    }

    public void recordCounterValue(String metricName, SortedMap<String, String> inputLabels, int value) {
        Key key = Key.builder()
                .metricName(metricName)
                .labelNames(new ArrayList<>(inputLabels.keySet()))
                .labelValues(new ArrayList<>(inputLabels.values()))
                .build();
        try {
            AtomicLong atomicLong = counters.get(key, () -> {
                log.debug("Creating counter {}{}", key.metricName, inputLabels);
                return new AtomicLong();
            });
            atomicLong.addAndGet(value);
        } catch (ExecutionException e) {
            log.error("Failed to get counter", e);
        }
    }

    public void recordLatency(String metricName, SortedMap<String, String> inputLabels, double value) {
        Key key = Key.builder()
                .metricName(metricName)
                .labelNames(new ArrayList<>(inputLabels.keySet()))
                .labelValues(new ArrayList<>(inputLabels.values()))
                .build();
        try {
            latencyCounters.get(key, () -> {
                log.debug("Creating latency count counter {}{}", key.metricName + "_count", inputLabels);
                log.debug("Creating latency total counter {}{}", key.metricName + "_sum", inputLabels);
                return new LatencyCounter();
            }).increment(value);
        } catch (ExecutionException e) {
            log.error("Failed to get latency counter", e);
        }
    }

    public void recordHistogram(String metricName, SortedMap<String, String> inputLabels, double value) {
        Key key = Key.builder()
                .metricName(metricName)
                .labelNames(new ArrayList<>(inputLabels.keySet()))
                .labelValues(new ArrayList<>(inputLabels.values()))
                .build();
        try {
            histograms.get(key, () -> {
                log.debug("Creating histogram {}{}", key.metricName + "_count", inputLabels);
                return Histogram.build()
                        .name(key.metricName)
                        .labelNames(key.labelNames.toArray(new String[0]))
                        .help("Histogram metric for " + key.metricName)
                        .create();
            }).labels(key.labelValues.toArray(new String[0])).observe(value);
        } catch (ExecutionException e) {
            log.error("Failed to get counter", e);
        }
    }


    @Builder
    @EqualsAndHashCode
    @ToString
    @Getter
    public static class Key {
        private final String metricName;
        private final List<String> labelNames;
        private final List<String> labelValues;
    }

    public static class LatencyCounter {
        private final AtomicDouble valueTotal = new AtomicDouble(0);
        private final AtomicInteger count = new AtomicInteger(0);

        public void increment(Double value) {
            valueTotal.addAndGet(value);
            count.incrementAndGet();
        }

        public int getCount() {
            return count.intValue();
        }

        public double getValue() {
            return valueTotal.doubleValue();
        }
    }

}
