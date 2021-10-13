/*
 * Copyright © 2021
 * Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.cloudwatch.prometheus;

import ai.asserts.aws.MetricNameUtil;
import ai.asserts.aws.cloudwatch.query.MetricQuery;
import io.prometheus.client.Collector;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import static io.prometheus.client.Collector.Type.GAUGE;

@EqualsAndHashCode(callSuper = false)
@Slf4j
public class GaugeCollector extends Collector {
    private final MetricNameUtil metricNameUtil;
    private final LabelBuilder labelBuilder;
    private final String metricName;
    private final String helpText;

    /**
     * The read write lock is used such that when the samples need to be collected we acquire the write lock and
     * when the samples are updated we use a read lock. This is because the sample update operations are fine-grained
     * and non-conflicting with each other. But the read operation reads all the samples. The conflict is really between
     * a coarse-grained read and multiple non-conflicting fine-grained reads. The writes behave like reads and the
     * read behaves like write. The thread safe behaviour of the {@link #samplesByMetric} for writes on non-conflicting
     * keys is taken care of by using a {@link ConcurrentHashMap} implementation
     */
    @EqualsAndHashCode.Exclude
    private final ReadWriteLock readWriteLock = new ReentrantReadWriteLock();
    private Map<Map<String, String>, MetricSamples> samplesByMetric = new ConcurrentHashMap<>();

    public GaugeCollector(MetricNameUtil metricNameUtil, LabelBuilder labelBuilder, String metricName, String helpText) {
        this.metricNameUtil = metricNameUtil;
        this.labelBuilder = labelBuilder;
        this.metricName = metricName;
        this.helpText = helpText;
    }

    @Override
    public List<MetricFamilySamples> collect() {
        Lock writeLock = readWriteLock.writeLock();
        writeLock.lock();
        List<MetricFamilySamples.Sample> samples = new ArrayList<>();
        samplesByMetric.forEach((labels, metricSamples) -> {
            log.debug("{}{} had {} samples while collecting", metricName, labels, metricSamples.metrics.size());
            metricSamples.getMetrics().forEach((ts, value) ->
                    samples.add(newSample(labels, ts, value)));
        });
        samplesByMetric = new ConcurrentHashMap<>();
        writeLock.unlock();

        List<MetricFamilySamples> metricFamilySamples = new ArrayList<>();
        metricFamilySamples.add(new MetricFamilySamples(metricName, GAUGE, helpText, samples));
        return metricFamilySamples;
    }

    public void addSample(String region, MetricQuery metricQuery, int period,
                          List<Instant> timestamps, List<Double> values) {
        // Build labels
        Map<String, String> labels = labelBuilder.buildLabels(region, metricQuery);
        for (int i = 0; i < timestamps.size(); i++) {
            addSample(labels, timestamps.get(i).plusSeconds(period).toEpochMilli(), values.get(i));
        }
    }

    public void addSample(Map<String, String> labels, Long timestamp, Double value) {
        Lock readLock = readWriteLock.readLock();
        readLock.lock();
        samplesByMetric.computeIfAbsent(labels, k -> new MetricSamples()).put(timestamp, value);
        readLock.unlock();
    }

    private MetricFamilySamples.Sample newSample(Map<String, String> labels, Long ts, Double value) {
        return new MetricFamilySamples.Sample(metricName,
                new ArrayList<>(labels.keySet()),
                new ArrayList<>(labels.values()),
                value, ts
        );
    }

    @Getter
    public static class MetricSamples {
        private final Map<Long, Double> metrics = new ConcurrentHashMap<>();

        public void put(Long ts, Double value) {
            metrics.put(ts, value);
        }
    }
}
