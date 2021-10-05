/*
 * Copyright © 2021
 * Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.cloudwatch.prometheus;

import io.prometheus.client.CollectorRegistry;
import org.easymock.EasyMockSupport;
import org.junit.jupiter.api.Test;

import static org.easymock.EasyMock.anyObject;
import static org.junit.jupiter.api.Assertions.assertSame;

public class MetricCollectorsTest extends EasyMockSupport {
    @Test
    void getGauge() {
        CollectorRegistry collectorRegistry = mock(CollectorRegistry.class);
        MetricCollectors metricCollectors = new MetricCollectors(collectorRegistry);
        collectorRegistry.register(anyObject(GaugeCollector.class));
        replayAll();
        GaugeCollector gaugeCollector = metricCollectors.getGauge("metric", "help");
        GaugeCollector gaugeCollector1 = metricCollectors.getGauge("metric", "help");
        assertSame(gaugeCollector, gaugeCollector1);
        verifyAll();
    }
}
