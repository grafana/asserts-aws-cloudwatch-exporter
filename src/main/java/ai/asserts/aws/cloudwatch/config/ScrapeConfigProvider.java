
package ai.asserts.aws.cloudwatch.config;

import ai.asserts.aws.AWSClientProvider;
import ai.asserts.aws.ObjectMapperFactory;
import ai.asserts.aws.RateLimiter;
import ai.asserts.aws.cloudwatch.model.CWNamespace;
import com.fasterxml.jackson.core.type.TypeReference;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableSortedMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static ai.asserts.aws.MetricNameUtil.SCRAPE_OPERATION_LABEL;

@Component
@Slf4j
public class ScrapeConfigProvider {
    private final ObjectMapperFactory objectMapperFactory;
    private final RateLimiter rateLimiter;
    private final S3Client s3Client;
    private final String scrapeConfigFile;
    private final ReadWriteLock readWriteLock = new ReentrantReadWriteLock();
    private volatile ScrapeConfig configCache;
    private final Map<String, CWNamespace> byNamespace = new TreeMap<>();
    private final Map<String, CWNamespace> byServiceName = new TreeMap<>();
    private final ResourceLoader resourceLoader = new FileSystemResourceLoader();

    public ScrapeConfigProvider(ObjectMapperFactory objectMapperFactory,
                                AWSClientProvider awsClientProvider,
                                RateLimiter rateLimiter,
                                @Value("${scrape.config.file:cloudwatch_scrape_config.yml}") String scrapeConfigFile) {
        this.objectMapperFactory = objectMapperFactory;
        this.scrapeConfigFile = scrapeConfigFile;
        this.rateLimiter = rateLimiter;
        this.s3Client = awsClientProvider.getS3Client();
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

    private ScrapeConfig load() {
        ScrapeConfig scrapeConfig;
        try {
            Map<String, String> envVariables = getGetenv();
            if (envVariables.containsKey("CONFIG_S3_BUCKET") && envVariables.containsKey("CONFIG_S3_KEY")) {
                String bucket = envVariables.get("CONFIG_S3_BUCKET");
                String key = envVariables.get("CONFIG_S3_KEY");
                log.info("Will load configuration from S3 Bucket [{}] and Key [{}]", bucket, key);
                ResponseBytes<GetObjectResponse> objectAsBytes = rateLimiter.doWithRateLimit(
                        "S3Client/getObjectAsBytes",
                        ImmutableSortedMap.of(SCRAPE_OPERATION_LABEL, "loadConfigFromS3"),
                        () -> s3Client.getObjectAsBytes(GetObjectRequest.builder()
                                .bucket(bucket)
                                .key(key)
                                .build()));
                scrapeConfig = objectMapperFactory.getObjectMapper().readValue(objectAsBytes.asInputStream(), new TypeReference<ScrapeConfig>() {
                });
            } else {
                log.info("Will load configuration from {}", scrapeConfigFile);
                Resource resource = resourceLoader.getResource(scrapeConfigFile);
                scrapeConfig = objectMapperFactory.getObjectMapper()
                        .readValue(resource.getURL(), new TypeReference<ScrapeConfig>() {
                        });
            }
            scrapeConfig.validateConfig();
            log.info("Loaded configuration");

            if (envVariables.containsKey("REGIONS")) {
                scrapeConfig.setRegions(Stream.of(envVariables.get("REGIONS").split(","))
                        .collect(Collectors.toSet()));
            }

            if (envVariables.containsKey("ENABLE_ECS_SD")) {
                scrapeConfig.setDiscoverECSTasks(isEnabled(System.getenv("ENABLE_ECS_SD")));
            }
            return scrapeConfig;
        } catch (IOException e) {
            log.error("Failed to load scrape configuration from file " + scrapeConfigFile, e);
            throw new UncheckedIOException(e);
        }
    }

    @VisibleForTesting
    Map<String, String> getGetenv() {
        return System.getenv();
    }

    public boolean isEnabled(String flag) {
        return Stream.of("y", "yes", "true").anyMatch(value -> value.equalsIgnoreCase(flag));
    }
}
