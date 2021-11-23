/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.cloudwatch.config;

import ai.asserts.aws.cloudwatch.model.MetricStat;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import org.easymock.EasyMockSupport;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.cloudwatch.model.Dimension;
import software.amazon.awssdk.services.cloudwatch.model.Metric;

import java.util.regex.Pattern;

import static org.easymock.EasyMock.expect;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class MetricConfigTest extends EasyMockSupport {
    @Test
    void getScrapeInterval() {
        NamespaceConfig namespaceConfig = mock(NamespaceConfig.class);
        MetricConfig metricConfig = MetricConfig.builder()
                .namespace(namespaceConfig)
                .build();

        expect(namespaceConfig.getScrapeInterval()).andReturn(60);
        replayAll();
        assertEquals(60, metricConfig.getScrapeInterval());
        verifyAll();

        metricConfig.setScrapeInterval(120);
        assertEquals(120, metricConfig.getScrapeInterval());
    }

    @Test
    void getPeriod() {
        NamespaceConfig namespaceConfig = mock(NamespaceConfig.class);
        MetricConfig metricConfig = MetricConfig.builder()
                .namespace(namespaceConfig)
                .build();

        expect(namespaceConfig.getPeriod()).andReturn(60);
        replayAll();
        assertEquals(60, metricConfig.getPeriod());
        verifyAll();

        metricConfig.setPeriod(120);
        assertEquals(120, metricConfig.getPeriod());
    }

    @Test
    void numSamplesPerScrape() {
        NamespaceConfig namespaceConfig = mock(NamespaceConfig.class);
        MetricConfig metricConfig = MetricConfig.builder()
                .namespace(namespaceConfig)
                .build();

        metricConfig.setPeriod(300);
        metricConfig.setScrapeInterval(60);
        assertEquals(1, metricConfig.numSamplesPerScrape());

        metricConfig.setPeriod(60);
        metricConfig.setScrapeInterval(300);
        assertEquals(5, metricConfig.numSamplesPerScrape());
    }

    @Test
    void validate_noName() {
        NamespaceConfig namespaceConfig = mock(NamespaceConfig.class);
        MetricConfig metricConfig = MetricConfig.builder()
                .namespace(namespaceConfig)
                .build();

        assertThrows(RuntimeException.class, () -> metricConfig.validate(0));
    }

    @Test
    void validate_invalidScrapeInterval() {
        NamespaceConfig namespaceConfig = mock(NamespaceConfig.class);
        MetricConfig metricConfig = MetricConfig.builder()
                .name("metric")
                .namespace(namespaceConfig)
                .scrapeInterval(5)
                .build();

        assertThrows(RuntimeException.class, () -> metricConfig.validate(0));

        metricConfig.setScrapeInterval(61);
        assertThrows(RuntimeException.class, () -> metricConfig.validate(0));
    }

    @Test
    void validate_invalidPeriod() {
        NamespaceConfig namespaceConfig = mock(NamespaceConfig.class);
        MetricConfig metricConfig = MetricConfig.builder()
                .name("metric")
                .namespace(namespaceConfig)
                .period(5)
                .build();

        assertThrows(RuntimeException.class, () -> metricConfig.validate(0));

        metricConfig.setPeriod(61);
        assertThrows(RuntimeException.class, () -> metricConfig.validate(0));
    }

    @Test
    void validate_statsMissing() {
        NamespaceConfig namespaceConfig = mock(NamespaceConfig.class);
        MetricConfig metricConfig = MetricConfig.builder()
                .name("metric")
                .namespace(namespaceConfig)
                .scrapeInterval(60)
                .period(300)
                .build();

        assertThrows(RuntimeException.class, () -> metricConfig.validate(0));
    }

    @Test
    void validate() {
        NamespaceConfig namespaceConfig = mock(NamespaceConfig.class);
        MetricConfig metricConfig = MetricConfig.builder()
                .name("metric")
                .namespace(namespaceConfig)
                .scrapeInterval(60)
                .period(300)
                .stats(ImmutableSet.of(MetricStat.Sum))
                .build();
        metricConfig.validate(0);
    }

    @Test
    void matches_DimensionPattern_NoFilter() {
        NamespaceConfig namespaceConfig = mock(NamespaceConfig.class);
        MetricConfig metricConfig = MetricConfig.builder()
                .name("metric")
                .namespace(namespaceConfig)
                .scrapeInterval(60)
                .period(300)
                .stats(ImmutableSet.of(MetricStat.Sum))
                .build();

        expect(namespaceConfig.getDimensionFilterPattern()).andReturn(ImmutableMap.of()).anyTimes();

        replayAll();
        assertTrue(metricConfig.matchesMetric(Metric.builder()
                .build()));
        assertTrue(metricConfig.matchesMetric(Metric.builder()
                .dimensions(Dimension.builder()
                        .name("FunctionName")
                        .value("fn-1")
                        .build())
                .build()));
        verifyAll();
    }

    @Test
    void matches_DimensionPattern_FilterSpecified() {
        NamespaceConfig namespaceConfig = mock(NamespaceConfig.class);
        MetricConfig metricConfig = MetricConfig.builder()
                .name("metric")
                .namespace(namespaceConfig)
                .scrapeInterval(60)
                .period(300)
                .stats(ImmutableSet.of(MetricStat.Sum))
                .build();

        expect(namespaceConfig.getDimensionFilterPattern()).andReturn(ImmutableMap.of(
                "FunctionName", Pattern.compile("fn.*")
        )).anyTimes();

        replayAll();
        assertFalse(metricConfig.matchesMetric(Metric.builder()
                .build()));
        assertFalse(metricConfig.matchesMetric(Metric.builder()
                .dimensions(Dimension.builder()
                        .name("FunctionName")
                        .value("function-1")
                        .build())
                .build()));
        assertTrue(metricConfig.matchesMetric(Metric.builder()
                .dimensions(Dimension.builder()
                        .name("FunctionName")
                        .value("fn-1")
                        .build())
                .build()));
        verifyAll();
    }
}
