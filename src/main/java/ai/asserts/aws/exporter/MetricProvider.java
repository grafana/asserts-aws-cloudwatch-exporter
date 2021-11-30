/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.exporter;

import io.prometheus.client.Collector;

import java.util.List;

public interface MetricProvider {
    List<Collector.MetricFamilySamples> collect();
    void update();
}
