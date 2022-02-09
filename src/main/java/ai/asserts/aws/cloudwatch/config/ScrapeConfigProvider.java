
package ai.asserts.aws.cloudwatch.config;

import ai.asserts.aws.ObjectMapperFactory;
import ai.asserts.aws.cloudwatch.model.CWNamespace;
import com.fasterxml.jackson.core.type.TypeReference;
import com.google.common.annotations.VisibleForTesting;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
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
    private final ResourceLoader resourceLoader = new FileSystemResourceLoader();
    private final String scrapeConfigFile;
    private final ReadWriteLock readWriteLock = new ReentrantReadWriteLock();
    private volatile ScrapeConfig configCache;
    private final Map<String, CWNamespace> byNamespace = new TreeMap<>();
    private final Map<String, CWNamespace> byServiceName = new TreeMap<>();

    public ScrapeConfigProvider(ObjectMapperFactory objectMapperFactory,
                                @Value("${scrape.config.file:cloudwatch_scrape_config.yml}") String scrapeConfigFile) {
        this.objectMapperFactory = objectMapperFactory;
        this.scrapeConfigFile = scrapeConfigFile;
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

    @VisibleForTesting
    public void validateConfig(ScrapeConfig scrapeConfig) {
        if (!CollectionUtils.isEmpty(scrapeConfig.getNamespaces())) {
            for (int i = 0; i < scrapeConfig.getNamespaces().size(); i++) {
                NamespaceConfig namespaceConfig = scrapeConfig.getNamespaces().get(i);
                namespaceConfig.setScrapeConfig(scrapeConfig);
                namespaceConfig.validate(i);
            }
        }

        if (!CollectionUtils.isEmpty(scrapeConfig.getEcsTaskScrapeConfigs())) {
            scrapeConfig.getEcsTaskScrapeConfigs().forEach(ECSTaskDefScrapeConfig::validate);
        }
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
        URL url;
        try {
            Resource resource = resourceLoader.getResource(scrapeConfigFile);
            url = resource.getURL();
            ScrapeConfig scrapeConfig = objectMapperFactory.getObjectMapper()
                    .readValue(url, new TypeReference<ScrapeConfig>() {
                    });

            validateConfig(scrapeConfig);

            Map<String, String> getenv = getGetenv();
            if (getenv.containsKey("REGIONS")) {
                scrapeConfig.setRegions(Stream.of(getenv.get("REGIONS").split(","))
                        .collect(Collectors.toSet()));
            }

            if (getenv.containsKey("ENABLE_ECS_SD")) {
                scrapeConfig.setDiscoverECSTasks(isEnabled(System.getenv("ENABLE_ECS_SD")));
            }

            log.info("Loaded cloudwatch scrape configuration from url={}", url);
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
