package ai.asserts.aws;

import ai.asserts.aws.config.MetricConfig;
import ai.asserts.aws.config.NamespaceConfig;
import ai.asserts.aws.config.RelabelConfig;
import ai.asserts.aws.config.ScrapeConfig;
import ai.asserts.aws.model.MetricStat;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import org.easymock.EasyMockSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.easymock.EasyMock.expect;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ScrapeConfigProviderTest extends EasyMockSupport {
    private S3Client s3Client;
    private RestTemplate restTemplate;
    private static List<RelabelConfig> relabelConfigs;

    @BeforeAll
    public static void onlyOnce() {
        try {
            File file = new File("src/dist/conf/default_relabel_rules.yml");
            ObjectMapper objectMapper = new ObjectMapperFactory().getObjectMapper();
            relabelConfigs = objectMapper.readValue(file, new TypeReference<List<RelabelConfig>>() {
            });
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @BeforeEach
    public void setup() {
        s3Client = mock(S3Client.class);
        restTemplate = mock(RestTemplate.class);
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
        scrapeConfig.validateConfig();
        assertEquals(60, namespaceConfig.getEffectiveScrapeInterval());
        assertEquals(60, metricConfig.getEffectiveScrapeInterval());
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
        scrapeConfig.validateConfig();
        assertEquals(600, namespaceConfig.getEffectiveScrapeInterval());
        assertEquals(600, metricConfig.getEffectiveScrapeInterval());
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
        scrapeConfig.validateConfig();
        assertEquals(600, namespaceConfig.getEffectiveScrapeInterval());
        assertEquals(600, metricConfig.getEffectiveScrapeInterval());
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
        scrapeConfig.validateConfig();
        assertEquals(600, namespaceConfig.getEffectiveScrapeInterval());
        assertEquals(900, metricConfig.getEffectiveScrapeInterval());
    }

    @Test
    void integrationTest() {
        ScrapeConfigProvider testClass = new ScrapeConfigProvider(
                new ObjectMapperFactory(),
                "src/test/resources/cloudwatch_scrape_config.yml",
                restTemplate);
        assertNotNull(testClass.getScrapeConfig());
        assertEquals(ImmutableSet.of("us-west-2"), testClass.getScrapeConfig().getRegions());
        assertTrue(testClass.getScrapeConfig().isDiscoverECSTasks());
        assertFalse(testClass.getScrapeConfig().getDimensionToLabels().isEmpty());
    }

    @Test
    void envOverrides() {
        ScrapeConfigProvider testClass = new ScrapeConfigProvider(
                new ObjectMapperFactory(),
                "src/test/resources/cloudwatch_scrape_config.yml",
                restTemplate) {
            @Override
            Map<String, String> getGetenv() {
                return ImmutableMap.of("REGIONS", "us-east-1,us-east-2", "ENABLE_ECS_SD", "false");
            }
        };
        assertNotNull(testClass.getScrapeConfig());
        assertFalse(testClass.getScrapeConfig().isDiscoverECSTasks());
        assertEquals(ImmutableSet.of("us-east-1", "us-east-2"), testClass.getScrapeConfig().getRegions());
    }

    @Test
    void loadConfigFromS3() throws IOException {
        FileInputStream fis = new FileInputStream("src/test/resources/cloudwatch_scrape_config.yml");
        ScrapeConfig scrapeConfig = new ObjectMapperFactory().getObjectMapper().readValue(fis, new TypeReference<ScrapeConfig>() {
        });
        scrapeConfig.getRelabelConfigs().addAll(relabelConfigs);
        scrapeConfig.validateConfig();

        fis = new FileInputStream("src/test/resources/cloudwatch_scrape_config.yml");
        expect(s3Client.getObjectAsBytes(GetObjectRequest.builder()
                .bucket("bucket")
                .key("key")
                .build())).andReturn(ResponseBytes.fromInputStream(GetObjectResponse.builder().build(), fis));
        replayAll();
        ScrapeConfigProvider testClass = new ScrapeConfigProvider(
                new ObjectMapperFactory(),
                "src/test/resources/cloudwatch_scrape_config.yml",
                restTemplate) {
            @Override
            Map<String, String> getGetenv() {
                return ImmutableMap.of("CONFIG_S3_BUCKET", "bucket", "CONFIG_S3_KEY", "key");
            }

            @Override
            S3Client getS3Client() {
                return s3Client;
            }
        };
        ScrapeConfig actualConfig = testClass.getScrapeConfig();
        actualConfig.validateConfig();
        ObjectWriter writer = new ObjectMapperFactory().getObjectMapper()
                .writerWithDefaultPrettyPrinter();
        assertEquals(writer.writeValueAsString(scrapeConfig), writer.writeValueAsString(actualConfig));
        verifyAll();
    }

    @Test
    void loadConfigFromAsserts() throws IOException {
        FileInputStream fis = new FileInputStream("src/test/resources/cloudwatch_scrape_config.yml");
        ScrapeConfig scrapeConfig = new ObjectMapperFactory().getObjectMapper().readValue(fis,
                new TypeReference<ScrapeConfig>() {
                });
        scrapeConfig.validateConfig();

        HttpHeaders headers = new HttpHeaders();
        headers.setBasicAuth("user", "key");
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<?> httpEntity = new HttpEntity<>(headers);
        ResponseEntity<ScrapeConfig> response = new ResponseEntity(scrapeConfig, HttpStatus.OK);
        expect(restTemplate.exchange("host/api-server/v1/config/aws-exporter",
                HttpMethod.GET,
                httpEntity,
                new ParameterizedTypeReference<ScrapeConfig>() {
                }
        )).andReturn(response);
        replayAll();
        ScrapeConfigProvider testClass = new ScrapeConfigProvider(
                new ObjectMapperFactory(),
                "src/test/resources/cloudwatch_scrape_config.yml",
                restTemplate) {
            @Override
            Map<String, String> getGetenv() {
                return ImmutableMap.of("ASSERTS_API_SERVER_URL", "host", "ASSERTS_USER", "user",
                        "ASSERTS_PASSWORD", "key");
            }

        };
        ObjectWriter writer = new ObjectMapperFactory().getObjectMapper()
                .writerWithDefaultPrettyPrinter();
        assertEquals(writer.writeValueAsString(scrapeConfig), writer.writeValueAsString(testClass.getScrapeConfig()));
        verifyAll();
    }
}
