/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.cloudwatch.prometheus;

import io.prometheus.client.Collector;

import java.util.List;

public interface MetricProvider {
    List<Collector.MetricFamilySamples> collect();
    void update();
}
