
package ai.asserts.aws.cloudwatch.config;

import ai.asserts.aws.ObjectMapperFactory;
import ai.asserts.aws.cloudwatch.model.MetricStat;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import org.easymock.EasyMockSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        assertEquals(60, metricConfig.getScrapeInterval());
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
                .namespaces(ImmutableList.of(namespaceConfig))
                .build();
        testClass.validateConfig(scrapeConfig);
        assertEquals(600, namespaceConfig.getScrapeInterval());
        assertEquals(600, metricConfig.getScrapeInterval());
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
                .metrics(ImmutableList.of(metricConfig))
                .dimensionFilters(ImmutableMap.of("dimension1", "pattern"))
                .build();
        ScrapeConfig scrapeConfig = ScrapeConfig.builder()
                .regions(ImmutableSet.of("region1"))
                .scrapeInterval(60)
                .namespaces(ImmutableList.of(namespaceConfig))
                .build();
        testClass.validateConfig(scrapeConfig);
        assertEquals(600, namespaceConfig.getScrapeInterval());
        assertEquals(600, metricConfig.getScrapeInterval());
    }

    @Test
    void validWith_No_Defaults() {
        MetricConfig metricConfig = MetricConfig.builder()
                .name("Invocations")
                .stats(ImmutableSet.of(MetricStat.Sum))
                .scrapeInterval(900)
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
                .namespaces(ImmutableList.of(namespaceConfig))
                .build();
        testClass.validateConfig(scrapeConfig);
        assertEquals(600, namespaceConfig.getScrapeInterval());
        assertEquals(900, metricConfig.getScrapeInterval());
    }

    @Test
    void integrationTest() {
        ScrapeConfigProvider testClass = new ScrapeConfigProvider(
                new ObjectMapperFactory(),
                "src/test/resources/cloudwatch_scrape_config.yml");
        assertNotNull(testClass.getScrapeConfig());
        assertEquals(ImmutableSet.of("us-west-2"), testClass.getScrapeConfig().getRegions());
        assertTrue(testClass.getScrapeConfig().isDiscoverECSTasks());
    }

    @Test
    void envOverrides() {
        ScrapeConfigProvider testClass = new ScrapeConfigProvider(
                new ObjectMapperFactory(),
                "src/test/resources/cloudwatch_scrape_config.yml") {
            @Override
            Map<String, String> getGetenv() {
                return ImmutableMap.of("REGIONS", "us-east-1,us-east-2", "ENABLE_ECS_SD", "false");
            }
        };
        assertNotNull(testClass.getScrapeConfig());
        assertFalse(testClass.getScrapeConfig().isDiscoverECSTasks());
        assertEquals(ImmutableSet.of("us-east-1", "us-east-2"), testClass.getScrapeConfig().getRegions());
    }
}
