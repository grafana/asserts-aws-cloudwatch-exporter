
package ai.asserts.aws.cloudwatch.config;

import ai.asserts.aws.cloudwatch.model.CWNamespace;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Suppliers;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Supplier;
import java.util.stream.Stream;

@Component
@Slf4j
public class ScrapeConfigProvider {

    private final ObjectMapper objectMapper = new ObjectMapper(new YAMLFactory()
            .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
            .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES))
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);
    private final ResourceLoader resourceLoader = new FileSystemResourceLoader();
    private final String scrapeConfigFile;
    private final Supplier<ScrapeConfig> configCache;
    private final Map<String, CWNamespace> byNamespace = new TreeMap<>();
    private final Map<String, CWNamespace> byServiceName = new TreeMap<>();

    public ScrapeConfigProvider(@Value("${scrape.config.file:cloudwatch_scrape_config.yml}") String scrapeConfigFile) {
        this.scrapeConfigFile = scrapeConfigFile;
        configCache = Suppliers.memoize(this::load);
        Stream.of(CWNamespace.values()).forEach(namespace -> {
            byNamespace.put(namespace.getNamespace(), namespace);
            byServiceName.put(namespace.getServiceName(), namespace);
        });
    }

    public ScrapeConfig getScrapeConfig() {
        return configCache.get();
    }

    private ScrapeConfig load() {
        URL url;
        try {
            Resource resource = resourceLoader.getResource(scrapeConfigFile);
            url = resource.getURL();
            ScrapeConfig scrapeConfig = objectMapper.readValue(url, new TypeReference<ScrapeConfig>() {
            });

            validateConfig(scrapeConfig);
            log.info("Loaded cloudwatch scrape configuration from url={}", url);
            return scrapeConfig;
        } catch (IOException e) {
            log.error("Failed to load scrape configuration from file " + scrapeConfigFile, e);
            throw new UncheckedIOException(e);
        }
    }

    @VisibleForTesting
    public void validateConfig(ScrapeConfig scrapeConfig) {
        for (int i = 0; i < scrapeConfig.getNamespaces().size(); i++) {
            NamespaceConfig namespaceConfig = scrapeConfig.getNamespaces().get(i);
            namespaceConfig.setScrapeConfig(scrapeConfig);
            namespaceConfig.validate(i);
        }
    }

    public Optional<CWNamespace> getStandardNamespace(String namespace) {
        return Optional.ofNullable(byNamespace.getOrDefault(namespace,
                byServiceName.getOrDefault(namespace, null)));
    }
}
