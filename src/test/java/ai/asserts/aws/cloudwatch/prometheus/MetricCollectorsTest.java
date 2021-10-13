/*
 * Copyright © 2021
 * Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.cloudwatch.prometheus;

import ai.asserts.aws.MetricNameUtil;
import io.prometheus.client.CollectorRegistry;
import org.easymock.EasyMockSupport;
import org.junit.jupiter.api.Test;

import static org.easymock.EasyMock.anyObject;
import static org.junit.jupiter.api.Assertions.assertSame;

public class MetricCollectorsTest extends EasyMockSupport {
    @Test
    void getGauge() {
        MetricNameUtil metricNameUtil = mock(MetricNameUtil.class);
        LabelBuilder labelBuilder = mock(LabelBuilder.class);
        CollectorRegistry collectorRegistry = mock(CollectorRegistry.class);
        MetricCollectors metricCollectors = new MetricCollectors(collectorRegistry, metricNameUtil, labelBuilder);
        collectorRegistry.register(anyObject(GaugeCollector.class));
        replayAll();
        GaugeCollector gaugeCollector = metricCollectors.getGauge("metric", "help");
        GaugeCollector gaugeCollector1 = metricCollectors.getGauge("metric", "help");
        assertSame(gaugeCollector, gaugeCollector1);
        verifyAll();
    }
}
