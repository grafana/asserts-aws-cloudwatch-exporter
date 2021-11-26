
package ai.asserts.aws.cloudwatch.config;

import ai.asserts.aws.ObjectMapperFactory;
import ai.asserts.aws.cloudwatch.model.MetricStat;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import org.easymock.EasyMockSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class ScrapeConfigProviderTest extends EasyMockSupport {
    private ScrapeConfigProvider testClass;

    @BeforeEach
    public void setup() {
        testClass = new ScrapeConfigProvider(new ObjectMapperFactory(),
                "cloudwatch_scrape_config.yml");
    }

    @Test
    void validWithDefaults() {
        MetricConfig metricConfig = MetricConfig.builder()
                .name("Invocations")
                .stats(ImmutableSet.of(MetricStat.Sum))
                .build();
        NamespaceConfig namespaceConfig = NamespaceConfig.builder()
                .name("AWS/Lambda")
                .metrics(ImmutableList.of(metricConfig))
                .build();
        ScrapeConfig scrapeConfig = ScrapeConfig.builder()
                .regions(ImmutableSet.of("region1"))
                .namespaces(ImmutableList.of(namespaceConfig))
                .build();
        testClass.validateConfig(scrapeConfig);
        assertEquals(60, namespaceConfig.getScrapeInterval());
        assertEquals(60, namespaceConfig.getPeriod());
        assertEquals(60, metricConfig.getScrapeInterval());
        assertEquals(60, metricConfig.getPeriod());
    }

    @Test
    void validWith_TopLevel_Defaults() {
        MetricConfig metricConfig = MetricConfig.builder()
                .name("Invocations")
                .stats(ImmutableSet.of(MetricStat.Sum))
                .build();
        NamespaceConfig namespaceConfig = NamespaceConfig.builder()
                .name("AWS/Lambda")
                .metrics(ImmutableList.of(metricConfig))
                .build();
        ScrapeConfig scrapeConfig = ScrapeConfig.builder()
                .regions(ImmutableSet.of("region1"))
                .scrapeInterval(600)
                .period(300)
                .namespaces(ImmutableList.of(namespaceConfig))
                .build();
        testClass.validateConfig(scrapeConfig);
        assertEquals(600, namespaceConfig.getScrapeInterval());
        assertEquals(300, namespaceConfig.getPeriod());
        assertEquals(600, metricConfig.getScrapeInterval());
        assertEquals(300, metricConfig.getPeriod());
    }

    @Test
    void validWith_NSLevel_Defaults() {
        MetricConfig metricConfig = MetricConfig.builder()
                .name("Invocations")
                .stats(ImmutableSet.of(MetricStat.Sum))
                .build();
        NamespaceConfig namespaceConfig = NamespaceConfig.builder()
                .name("AWS/Lambda")
                .scrapeInterval(600)
                .period(300)
                .metrics(ImmutableList.of(metricConfig))
                .dimensionFilters(ImmutableMap.of("dimension1", "pattern"))
                .build();
        ScrapeConfig scrapeConfig = ScrapeConfig.builder()
                .regions(ImmutableSet.of("region1"))
                .scrapeInterval(60)
                .period(300)
                .namespaces(ImmutableList.of(namespaceConfig))
                .build();
        testClass.validateConfig(scrapeConfig);
        assertEquals(600, namespaceConfig.getScrapeInterval());
        assertEquals(300, namespaceConfig.getPeriod());
        assertEquals(600, metricConfig.getScrapeInterval());
        assertEquals(300, metricConfig.getPeriod());
    }

    @Test
    void validWith_No_Defaults() {
        MetricConfig metricConfig = MetricConfig.builder()
                .name("Invocations")
                .stats(ImmutableSet.of(MetricStat.Sum))
                .scrapeInterval(900)
                .period(60)
                .build();
        NamespaceConfig namespaceConfig = NamespaceConfig.builder()
                .name("AWS/Lambda")
                .scrapeInterval(600)
                .period(300)
                .metrics(ImmutableList.of(metricConfig))
                .build();
        ScrapeConfig scrapeConfig = ScrapeConfig.builder()
                .regions(ImmutableSet.of("region1"))
                .scrapeInterval(60)
                .period(300)
                .namespaces(ImmutableList.of(namespaceConfig))
                .build();
        testClass.validateConfig(scrapeConfig);
        assertEquals(600, namespaceConfig.getScrapeInterval());
        assertEquals(300, namespaceConfig.getPeriod());
        assertEquals(900, metricConfig.getScrapeInterval());
        assertEquals(60, metricConfig.getPeriod());
    }

    @Test
    void integrationTest() {
        ScrapeConfigProvider testClass = new ScrapeConfigProvider(
                new ObjectMapperFactory(),
                "src/test/resources/cloudwatch_scrape_config.yml");
        assertNotNull(testClass.getScrapeConfig());
    }
}
