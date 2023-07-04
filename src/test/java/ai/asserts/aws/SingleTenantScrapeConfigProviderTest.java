package ai.asserts.aws;

import ai.asserts.aws.config.MetricConfig;
import ai.asserts.aws.config.NamespaceConfig;
import ai.asserts.aws.config.ScrapeConfig;
import ai.asserts.aws.model.MetricStat;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import org.easymock.EasyMockSupport;
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

import java.io.FileInputStream;
import java.io.IOException;
import java.util.Map;

import static ai.asserts.aws.ApiServerConstants.ASSERTS_TENANT_HEADER;
import static org.easymock.EasyMock.expect;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class SingleTenantScrapeConfigProviderTest extends EasyMockSupport {
    private EnvironmentConfig environmentConfig;
    private S3Client s3Client;
    private RestTemplate restTemplate;
    private SnakeCaseUtil snakeCaseUtil;
    private AssertsServerUtil assertsServerUtil;

    @BeforeEach
    public void setup() {
        environmentConfig = mock(EnvironmentConfig.class);
        s3Client = mock(S3Client.class);
        restTemplate = mock(RestTemplate.class);
        assertsServerUtil = mock(AssertsServerUtil.class);
        snakeCaseUtil = new SnakeCaseUtil();
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
        expect(environmentConfig.isEnabled()).andReturn(true);
        expect(environmentConfig.isDisabled()).andReturn(false);
        replayAll();
        SingleTenantScrapeConfigProvider testClass = new SingleTenantScrapeConfigProvider(
                environmentConfig,
                new ObjectMapperFactory(),
                "src/test/resources/cloudwatch_scrape_config.yml",
                restTemplate, snakeCaseUtil, assertsServerUtil);
        assertNotNull(testClass.getScrapeConfig("null"));
        assertEquals(ImmutableSet.of("us-west-2"), testClass.getScrapeConfig("null").getRegions());
        assertTrue(testClass.getScrapeConfig("null").isDiscoverECSTasks());
        Map<String, MetricConfig> metricsToCapture = testClass.getScrapeConfig("null").getMetricsToCapture();
        assertEquals(17, metricsToCapture.size());
        assertTrue(metricsToCapture.containsKey("aws_lambda_invocations_sum"));
        assertTrue(metricsToCapture.containsKey("aws_lambda_errors_sum"));
    }

    @Test
    void envOverrides() {
        expect(environmentConfig.isEnabled()).andReturn(true);
        expect(environmentConfig.isDisabled()).andReturn(false);
        replayAll();
        SingleTenantScrapeConfigProvider testClass = new SingleTenantScrapeConfigProvider(
                environmentConfig,
                new ObjectMapperFactory(),
                "src/test/resources/cloudwatch_scrape_config.yml",
                restTemplate, snakeCaseUtil, assertsServerUtil) {
            @Override
            Map<String, String> getGetenv() {
                return ImmutableMap.of("REGIONS", "us-east-1,us-east-2", "ENABLE_ECS_SD", "false");
            }
        };
        assertNotNull(testClass.getScrapeConfig("null"));
        assertFalse(testClass.getScrapeConfig("null").isDiscoverECSTasks());
        assertEquals(ImmutableSet.of("us-east-1", "us-east-2"), testClass.getScrapeConfig("null").getRegions());
    }

    @Test
    void loadConfigFromS3() throws IOException {
        FileInputStream fis = new FileInputStream("src/test/resources/cloudwatch_scrape_config.yml");
        ScrapeConfig scrapeConfig =
                new ObjectMapperFactory().getObjectMapper().readValue(fis, new TypeReference<ScrapeConfig>() {
                });
        scrapeConfig.validateConfig();

        fis = new FileInputStream("src/test/resources/cloudwatch_scrape_config.yml");
        expect(s3Client.getObjectAsBytes(GetObjectRequest.builder()
                .bucket("bucket")
                .key("key")
                .build())).andReturn(ResponseBytes.fromInputStream(GetObjectResponse.builder().build(), fis));
        expect(environmentConfig.isEnabled()).andReturn(true);
        expect(environmentConfig.isDisabled()).andReturn(false);
        replayAll();

        SingleTenantScrapeConfigProvider testClass = new SingleTenantScrapeConfigProvider(
                environmentConfig,
                new ObjectMapperFactory(),
                "src/test/resources/cloudwatch_scrape_config.yml",
                restTemplate, snakeCaseUtil, assertsServerUtil) {
            @Override
            Map<String, String> getGetenv() {
                return ImmutableMap.of("CONFIG_S3_BUCKET", "bucket", "CONFIG_S3_KEY", "key");
            }

            @Override
            S3Client getS3Client() {
                return s3Client;
            }
        };
        ScrapeConfig actualConfig = testClass.getScrapeConfig("null");
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
        headers.add(ASSERTS_TENANT_HEADER, "user");
        HttpEntity<String> httpEntity = new HttpEntity<>(headers);
        ResponseEntity<ScrapeConfig> response = new ResponseEntity<>(scrapeConfig, HttpStatus.OK);
        expect(assertsServerUtil.getExporterConfigUrl()).andReturn("host/api-server/v1/config/aws-exporter");
        expect(assertsServerUtil.createAssertsAuthHeader()).andReturn(httpEntity);
        expect(restTemplate.exchange("host/api-server/v1/config/aws-exporter",
                HttpMethod.GET,
                httpEntity,
                new ParameterizedTypeReference<ScrapeConfig>() {
                }
        )).andReturn(response);
        expect(environmentConfig.isEnabled()).andReturn(true);
        expect(environmentConfig.isDisabled()).andReturn(false);
        replayAll();
        SingleTenantScrapeConfigProvider testClass = new SingleTenantScrapeConfigProvider(
                environmentConfig,
                new ObjectMapperFactory(),
                "src/test/resources/cloudwatch_scrape_config.yml",
                restTemplate, snakeCaseUtil, assertsServerUtil) {
            @Override
            Map<String, String> getGetenv() {
                return ImmutableMap.of("ASSERTS_API_SERVER_URL", "host", "ASSERTS_USER", "user",
                        "ASSERTS_PASSWORD", "key");
            }

        };
        ObjectWriter writer = new ObjectMapperFactory().getObjectMapper()
                .writerWithDefaultPrettyPrinter();
        assertEquals(writer.writeValueAsString(scrapeConfig),
                writer.writeValueAsString(testClass.getScrapeConfig("null")));
        verifyAll();
    }
}
