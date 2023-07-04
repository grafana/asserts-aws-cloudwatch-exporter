package ai.asserts.aws;

import ai.asserts.aws.config.ScrapeConfig;
import ai.asserts.aws.model.CWNamespace;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.annotations.VisibleForTesting;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.FileSystemResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static ai.asserts.aws.ApiServerConstants.ASSERTS_API_SERVER_URL;

@Component
@Slf4j
@ConditionalOnProperty(name = "aws_exporter.tenant_mode", havingValue = "single", matchIfMissing = true)
public class SingleTenantScrapeConfigProvider implements ScrapeConfigProvider {
    private final EnvironmentConfig environmentConfig;
    private final ObjectMapperFactory objectMapperFactory;
    private final String scrapeConfigFile;
    private final ReadWriteLock readWriteLock = new ReentrantReadWriteLock();
    private final Map<String, CWNamespace> byNamespace = new TreeMap<>();
    private final Map<String, CWNamespace> byServiceName = new TreeMap<>();
    private final ResourceLoader resourceLoader = new FileSystemResourceLoader();
    private final ScrapeConfig NOOP_CONFIG = new ScrapeConfig();
    private final RestTemplate restTemplate;
    private final SnakeCaseUtil snakeCaseUtil;
    private final AssertsServerUtil assertsServerUtil;
    private volatile ScrapeConfig configCache;

    public SingleTenantScrapeConfigProvider(EnvironmentConfig environmentConfig,
                                            ObjectMapperFactory objectMapperFactory,
                                            @Value("${scrape.config.file:cloudwatch_scrape_config.yml}") String scrapeConfigFile,
                                            RestTemplate restTemplate,
                                            SnakeCaseUtil snakeCaseUtil,
                                            AssertsServerUtil assertsServerUtil) {
        this.environmentConfig = environmentConfig;
        this.objectMapperFactory = objectMapperFactory;
        this.scrapeConfigFile = scrapeConfigFile;
        this.restTemplate = restTemplate;
        this.snakeCaseUtil = snakeCaseUtil;
        this.assertsServerUtil = assertsServerUtil;
        log.info("Single Tenant Scrape Config Provider created");
        if (environmentConfig.isEnabled()) {
            loadAndBuildLookups();
        }
    }

    @Override
    public ScrapeConfig getScrapeConfig(String tenant) {
        try {
            readWriteLock.readLock().lock();
            return configCache;
        } finally {
            readWriteLock.readLock().unlock();
        }
    }

    public void update() {
        loadAndBuildLookups();
    }

    public Optional<CWNamespace> getStandardNamespace(String namespace) {
        return Optional.ofNullable(byNamespace.getOrDefault(namespace,
                byServiceName.getOrDefault(namespace, null)));
    }

    private void loadAndBuildLookups() {
        if (environmentConfig.isDisabled()) {
            log.info("All processing off");
            return;
        }
        readWriteLock.writeLock().lock();
        try {
            configCache = load();
            byNamespace.clear();
            byServiceName.clear();
            Stream.of(CWNamespace.values()).forEach(namespace -> {
                byNamespace.put(namespace.getNamespace(), namespace);
                byServiceName.put(namespace.getServiceName(), namespace);
            });
            buildMetricLookupMap(configCache);
        } catch (Exception e) {
            log.error("Failed to load config", e);
        } finally {
            readWriteLock.writeLock().unlock();
        }
    }

    private ScrapeConfig getConfigFromServer() {
        String url = assertsServerUtil.getExporterConfigUrl();
        log.info("Will load configuration from [{}]", url);
        ResponseEntity<ScrapeConfig> response = restTemplate.exchange(url,
                HttpMethod.GET,
                assertsServerUtil.createAssertsAuthHeader(),
                new ParameterizedTypeReference<ScrapeConfig>() {
                });
        if (response.getStatusCode().is2xxSuccessful()) {
            return response.getBody();
        }
        return NOOP_CONFIG;
    }

    private ScrapeConfig load() {
        ScrapeConfig scrapeConfig = NOOP_CONFIG;
        try {
            Map<String, String> envVariables = getGetenv();
            ObjectMapper objectMapper = objectMapperFactory.getObjectMapper();
            if (assertsEndpointConfigured(envVariables)) {
                scrapeConfig = getConfigFromServer();
            } else if (envVariables.containsKey("CONFIG_S3_BUCKET") && envVariables.containsKey("CONFIG_S3_KEY")) {
                try {
                    String bucket = envVariables.get("CONFIG_S3_BUCKET");
                    String key = envVariables.get("CONFIG_S3_KEY");
                    log.info("Will load configuration from S3 Bucket [{}] and Key [{}]", bucket, key);
                    S3Client s3Client = getS3Client();
                    ResponseBytes<GetObjectResponse> objectAsBytes =
                            s3Client.getObjectAsBytes(GetObjectRequest.builder()
                                    .bucket(bucket)
                                    .key(key)
                                    .build());
                    scrapeConfig =
                            objectMapper.readValue(objectAsBytes.asInputStream(), new TypeReference<ScrapeConfig>() {
                            });
                } catch (Exception e) {
                    log.error("Failed to load configuration from S3", e);
                }
            } else {
                if (scrapeConfig.isLogVerbose()) {
                    log.info("Will load configuration from {}", scrapeConfigFile);
                }
                Resource resource = resourceLoader.getResource(scrapeConfigFile);
                scrapeConfig = objectMapper
                        .readValue(resource.getURL(), new TypeReference<ScrapeConfig>() {
                        });
            }
            if (scrapeConfig.isLogScrapeConfig()) {
                log.info("Loaded configuration \n{}\n", objectMapper
                        .writerWithDefaultPrettyPrinter()
                        .writeValueAsString(scrapeConfig));
            }

            if (envVariables.containsKey("REGIONS")) {
                scrapeConfig.setRegions(Stream.of(envVariables.get("REGIONS").split(","))
                        .collect(Collectors.toSet()));
            }

            if (envVariables.containsKey("ENABLE_ECS_SD")) {
                scrapeConfig.setDiscoverECSTasks(isEnabled(System.getenv("ENABLE_ECS_SD")));
            }

            scrapeConfig.validateConfig();
            return scrapeConfig;
        } catch (IOException e) {
            log.error("Failed to load scrape configuration from file " + scrapeConfigFile, e);
            return NOOP_CONFIG;
        } catch (Exception e) {
            log.error("Failed to load aws exporter configuration", e);
            return NOOP_CONFIG;
        }
    }

    private void buildMetricLookupMap(ScrapeConfig finalScrapeConfig) {
        finalScrapeConfig.getNamespaces().forEach(ns -> ns.getMetrics().forEach(metricConfig -> {
            CWNamespace cwNamespace = byNamespace.get(metricConfig.getNamespace().getName());
            String prefix = cwNamespace.getMetricPrefix() +
                    "_" + metricConfig.getName();
            metricConfig.getStats().forEach(stat -> {
                String metricName = snakeCaseUtil.toSnakeCase(prefix + "_" + stat.getShortForm());
                finalScrapeConfig.getMetricsToCapture().put(metricName, metricConfig);
            });
        }));
    }

    private boolean assertsEndpointConfigured(Map<String, String> envVariables) {
        return envVariables.containsKey(ASSERTS_API_SERVER_URL)
                && envVariables.containsKey(ApiServerConstants.ASSERTS_USER)
                && envVariables.containsKey(ApiServerConstants.ASSERTS_PASSWORD);
    }

    @VisibleForTesting
    Map<String, String> getGetenv() {
        return System.getenv();
    }

    @VisibleForTesting
    S3Client getS3Client() {
        return S3Client.builder().build();
    }

    public boolean isEnabled(String flag) {
        return Stream.of("y", "yes", "true").anyMatch(value -> value.equalsIgnoreCase(flag));
    }
}
