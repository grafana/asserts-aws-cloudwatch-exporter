package ai.asserts.aws;

import ai.asserts.aws.config.RelabelConfig;
import ai.asserts.aws.config.ScrapeConfig;
import ai.asserts.aws.model.CWNamespace;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.annotations.VisibleForTesting;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.FileSystemResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Component
@Slf4j
public class ScrapeConfigProvider {
    private final ObjectMapperFactory objectMapperFactory;
    private final String scrapeConfigFile;
    private final ReadWriteLock readWriteLock = new ReentrantReadWriteLock();
    private final Map<String, CWNamespace> byNamespace = new TreeMap<>();
    private final Map<String, CWNamespace> byServiceName = new TreeMap<>();
    private final ResourceLoader resourceLoader = new FileSystemResourceLoader();
    private final ScrapeConfig NOOP_CONFIG = new ScrapeConfig();
    private final RestTemplate restTemplate;
    private volatile ScrapeConfig configCache;


    public ScrapeConfigProvider(ObjectMapperFactory objectMapperFactory,
                                @Value("${scrape.config.file:cloudwatch_scrape_config.yml}") String scrapeConfigFile,
                                RestTemplate restTemplate) {
        this.objectMapperFactory = objectMapperFactory;
        this.scrapeConfigFile = scrapeConfigFile;
        this.restTemplate = restTemplate;
        loadAndBuildLookups();
    }


    public ScrapeConfig getScrapeConfig() {
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
        try {
            readWriteLock.writeLock().lock();
            configCache = load();
            byNamespace.clear();
            byServiceName.clear();
            Stream.of(CWNamespace.values()).forEach(namespace -> {
                byNamespace.put(namespace.getNamespace(), namespace);
                byServiceName.put(namespace.getServiceName(), namespace);
            });
        } finally {
            readWriteLock.writeLock().unlock();
        }
    }

    private ScrapeConfig getConfig(Map<String, String> envVariables) {
        String host = envVariables.get(ApiServerConstants.ASSERTS_API_SERVER_URL);
        String user = envVariables.get(ApiServerConstants.ASSERTS_USER);
        String key = envVariables.get(ApiServerConstants.ASSERTS_PASSWORD);
        String url = host + "/api-server/v1/config/aws-exporter";
        log.info("Will load configuration from server [{}] with credentials of user [{}]", host, user);
        ResponseEntity<ScrapeConfig> response = restTemplate.exchange(url,
                HttpMethod.GET,
                createAuthHeader(user, key),
                new ParameterizedTypeReference<ScrapeConfig>() {
                });
        if (response.getStatusCode().is2xxSuccessful()) {
            return response.getBody();
        }
        return NOOP_CONFIG;
    }

    @VisibleForTesting
    HttpEntity<?> createAuthHeader(String username, String password) {
        if (StringUtils.hasText(username) && StringUtils.hasText(password)) {
            HttpHeaders headers = new HttpHeaders();
            headers.setBasicAuth(username, password);
            headers.setContentType(MediaType.APPLICATION_JSON);
            return new HttpEntity<>(headers);
        } else {
            return null;
        }
    }

    private ScrapeConfig load() {
        ScrapeConfig scrapeConfig = NOOP_CONFIG;
        try {
            Map<String, String> envVariables = getGetenv();
            ObjectMapper objectMapper = objectMapperFactory.getObjectMapper();
            if (envVariables.containsKey(ApiServerConstants.ASSERTS_API_SERVER_URL)
            && envVariables.containsKey(ApiServerConstants.ASSERTS_USER)
            && envVariables.containsKey(ApiServerConstants.ASSERTS_PASSWORD)) {
                scrapeConfig = getConfig(envVariables);
            } else if (envVariables.containsKey("CONFIG_S3_BUCKET") && envVariables.containsKey("CONFIG_S3_KEY")) {
                try {
                    String bucket = envVariables.get("CONFIG_S3_BUCKET");
                    String key = envVariables.get("CONFIG_S3_KEY");
                    log.info("Will load configuration from S3 Bucket [{}] and Key [{}]", bucket, key);
                    try (S3Client s3Client = getS3Client()) {
                        ResponseBytes<GetObjectResponse> objectAsBytes = s3Client.getObjectAsBytes(GetObjectRequest.builder()
                                .bucket(bucket)
                                .key(key)
                                .build());
                        scrapeConfig = objectMapper.readValue(objectAsBytes.asInputStream(), new TypeReference<ScrapeConfig>() {
                        });
                    }
                } catch (Exception e) {
                    log.error("Failed to load configuration from S3", e);
                }
            } else {
                log.info("Will load configuration from {}", scrapeConfigFile);
                Resource resource = resourceLoader.getResource(scrapeConfigFile);
                scrapeConfig = objectMapper
                        .readValue(resource.getURL(), new TypeReference<ScrapeConfig>() {
                        });
            }
            log.info("Loaded configuration");

            if (envVariables.containsKey("REGIONS")) {
                scrapeConfig.setRegions(Stream.of(envVariables.get("REGIONS").split(","))
                        .collect(Collectors.toSet()));
            }

            if (envVariables.containsKey("ENABLE_ECS_SD")) {
                scrapeConfig.setDiscoverECSTasks(isEnabled(System.getenv("ENABLE_ECS_SD")));
            }

            ScrapeConfig finalScrapeConfig = scrapeConfig;
            Stream.of("default_relabel_rules.yml", "src/dist/conf/default_relabel_rules.yml")
                    .map(File::new)
                    .filter(File::exists)
                    .findFirst().ifPresent(relabel_rules -> {
                try {
                    List<RelabelConfig> rules = objectMapper.readValue(relabel_rules, new TypeReference<List<RelabelConfig>>() {
                    });
                    finalScrapeConfig.getRelabelConfigs().addAll(rules);
                } catch (IOException e) {
                    log.error("Failed to load relabel rules", e);
                }
            });

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
