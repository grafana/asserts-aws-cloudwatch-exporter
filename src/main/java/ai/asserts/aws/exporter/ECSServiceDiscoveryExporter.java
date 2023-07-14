/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.exporter;

import ai.asserts.aws.EnvironmentConfig;
import ai.asserts.aws.ObjectMapperFactory;
import ai.asserts.aws.ScrapeConfigProvider;
import ai.asserts.aws.account.AccountTenantMapper;
import ai.asserts.aws.config.ScrapeConfig;
import ai.asserts.aws.config.ScrapeConfig.SubnetDetails;
import ai.asserts.aws.resource.ResourceMapper;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.google.common.annotations.VisibleForTesting;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.util.FileCopyUtils;
import org.springframework.web.client.RestTemplate;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static java.nio.file.Files.newOutputStream;
import static org.springframework.util.StringUtils.hasLength;

/**
 * Exports the Service Discovery file with the list of task instances running in ECS across clusters and services
 * within the clusters
 */
@Slf4j
@Component
public class ECSServiceDiscoveryExporter implements InitializingBean, Runnable {
    public static final String ECS_CONTAINER_METADATA_URI_V4 = "ECS_CONTAINER_METADATA_URI_V4";
    public static final String SCRAPE_ECS_SUBNETS = "SCRAPE_ECS_SUBNETS";
    public static final String SCRAPE_OVER_TLS = "SCRAPE_OVER_TLS";
    public static final String SD_FILE_PATH = "/opt/asserts/ecs-scrape-targets.yml";
    public static final String SD_FILE_PATH_SECURE = "/opt/asserts/ecs-scrape-targets-https.yml";
    private final EnvironmentConfig environmentConfig;
    private final RestTemplate restTemplate;
    private final ScrapeConfigProvider scrapeConfigProvider;
    private final ResourceMapper resourceMapper;
    private final ECSTaskUtil ecsTaskUtil;
    private final ObjectMapperFactory objectMapperFactory;

    private final ECSTaskProvider ecsTaskProvider;

    private final AccountIDProvider accountIDProvider;
    private final AccountTenantMapper accountTenantMapper;

    @Getter
    private final AtomicReference<SubnetDetails> subnetDetails = new AtomicReference<>(null);

    @Getter
    protected final Set<String> subnetsToScrape = new TreeSet<>();

    public ECSServiceDiscoveryExporter(EnvironmentConfig environmentConfig,
                                       RestTemplate restTemplate, AccountIDProvider accountIDProvider,
                                       ScrapeConfigProvider scrapeConfigProvider,
                                       ResourceMapper resourceMapper, ECSTaskUtil ecsTaskUtil,
                                       ObjectMapperFactory objectMapperFactory, ECSTaskProvider ecsTaskProvider,
                                       AccountTenantMapper accountTenantMapper) {
        this.environmentConfig = environmentConfig;
        this.restTemplate = restTemplate;
        this.scrapeConfigProvider = scrapeConfigProvider;
        this.resourceMapper = resourceMapper;
        this.ecsTaskUtil = ecsTaskUtil;
        this.objectMapperFactory = objectMapperFactory;
        this.ecsTaskProvider = ecsTaskProvider;
        this.accountIDProvider = accountIDProvider;
        this.accountTenantMapper = accountTenantMapper;
        if (environmentConfig.isEnabled()) {
            identifySubnetsToScrape();
        }
    }

    @VisibleForTesting
    void identifySubnetsToScrape() {
        if (System.getenv(SCRAPE_ECS_SUBNETS) != null) {
            this.subnetsToScrape.addAll(
                    Arrays.stream(System.getenv(SCRAPE_ECS_SUBNETS).split(","))
                            .map(String::trim)
                            .collect(Collectors.toSet()));
        }
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        if (environmentConfig.isEnabled()) {
            ClassPathResource classPathResource = new ClassPathResource("/dummy-ecs-targets.yml");
            File out = new File(SD_FILE_PATH);
            String src = classPathResource.getURI().toString();
            String dest = out.getAbsolutePath();
            try {
                FileCopyUtils.copy(classPathResource.getInputStream(), newOutputStream(out.toPath()));
                log.info("Copied dummy fd_config {} to {}", src, dest);
            } catch (Exception e) {
                log.error("Failed to copy dummy fd_config {} to {}", src, dest);
            }
            discoverSelfSubnet();
        }
    }

    /**
     * If the exporter is installed in multiple VPCs and multiple subnets in an AWS Account, only one of the exporters
     * will export the cloudwatch metrics and AWS Config metadata. The exporter doesn't automatically determine which
     * instance is primary. It has to be specified in the configuration by specifying either the VPC or the subnet or
     * both.
     *
     * @return <code>true</code> if this exporter should function as a primary exporter in this account.
     * <code>false</code> otherwise.
     */
    public boolean isPrimaryExporter() {
        String accountId = accountIDProvider.getAccountId();
        String tenantName = accountTenantMapper.getTenantName(accountId);
        ScrapeConfig scrapeConfig = scrapeConfigProvider.getScrapeConfig(tenantName);
        Map<String, SubnetDetails> primaryExportersByAccount = scrapeConfig.getPrimaryExporterByAccount();
        SubnetDetails primaryConfig = primaryExportersByAccount.get(accountId);
        return primaryConfig == null ||
                (!hasLength(primaryConfig.getVpcId()) || runningInVPC(primaryConfig.getVpcId())) &&
                        (!hasLength(primaryConfig.getSubnetId()) || runningInSubnet(primaryConfig.getSubnetId()));
    }

    public boolean runningInVPC(String vpcId) {
        if (subnetDetails.get() != null) {
            return vpcId.equals(subnetDetails.get().getVpcId());
        }
        return false;
    }

    public boolean runningInSubnet(String subnetId) {
        if (subnetDetails.get() != null) {
            return subnetId.equals(subnetDetails.get().getSubnetId());
        }
        return false;
    }

    @Override
    public void run() {
        if (environmentConfig.isSingleTenant()) {
            String tenantName = accountTenantMapper.getTenantName(accountIDProvider.getAccountId());
            ScrapeConfig scrapeConfig = scrapeConfigProvider.getScrapeConfig(tenantName);
            if (scrapeConfig.isDiscoverECSTasks()) {
                List<StaticConfig> targets = new ArrayList<>(ecsTaskProvider.getScrapeTargets());
                // If scrapes need to happen over TLS, split the configs into TLS and non-TLS.
                // The self scrape of the aws-exporter is a local scrape so doesn't need TLS
                if ("true".equalsIgnoreCase(getSSLFlag())) {
                    List<StaticConfig> exporterTarget = targets.stream()
                            .filter(config -> config.getLabels().getContainer().equals("cloudwatch-exporter"))
                            .collect(Collectors.toList());
                    targets.removeAll(exporterTarget);
                    writeFile(scrapeConfig, exporterTarget, SD_FILE_PATH);
                    writeFile(scrapeConfig, targets, SD_FILE_PATH_SECURE);
                } else {
                    writeFile(scrapeConfig, targets, SD_FILE_PATH);
                }
            }
        }
    }

    @VisibleForTesting
    void writeFile(ScrapeConfig scrapeConfig, List<StaticConfig> targets, String filePath) {
        try {
            File resultFile = new File(filePath);
            ObjectWriter objectWriter = objectMapperFactory.getObjectMapper().writerWithDefaultPrettyPrinter();
            List<StaticConfig> filteredTargets = targets.stream()
                    .filter(staticConfig -> shouldScrapeTargets(scrapeConfig, staticConfig))
                    .collect(Collectors.toList());
            objectWriter.writeValue(resultFile, filteredTargets);
            if (scrapeConfig.isLogECSTargets()) {
                String targetsFileContent = objectWriter.writeValueAsString(filteredTargets);
                log.info("Wrote ECS scrape target SD file {}\n{}\n", resultFile.toURI(), targetsFileContent);
            } else {
                log.info("Wrote ECS scrape target SD file {}", resultFile.toURI());
            }
        } catch (IOException e) {
            log.error("Failed to write ECS SD file", e);
        }
    }

    @VisibleForTesting
    boolean shouldScrapeTargets(ScrapeConfig scrapeConfig, StaticConfig config) {
        if( config.getTargets().isEmpty() ) {
            return false;
        }
        String targetVpc = config.getLabels().getVpcId();
        String targetSubnet = config.getLabels().getSubnetId();
        boolean vpcOK = scrapeConfig.isDiscoverECSTasksAcrossVPCs() ||
                (subnetDetails.get() != null && subnetDetails.get().getVpcId().equals(targetVpc));
        boolean subnetOK = subnetsToScrape.contains(targetSubnet) ||
                (subnetDetails.get() != null && subnetDetails.get().getSubnetId().equals(targetSubnet)) ||
                !scrapeConfig.isDiscoverOnlySubnetTasks();
        return vpcOK && subnetOK;
    }

    @VisibleForTesting
    String getMetaDataURI() {
        return System.getenv(ECS_CONTAINER_METADATA_URI_V4);
    }

    @VisibleForTesting
    void discoverSelfSubnet() {
        if (this.subnetDetails.get() == null) {
            String containerMetaURI = getMetaDataURI();
            log.info("Container stats scrape task got URI {}", containerMetaURI);
            if (containerMetaURI != null) {
                try {
                    String taskMetaDataURL = containerMetaURI + "/task";
                    URI uri = URI.create(taskMetaDataURL);
                    TaskMetaData taskMetaData = restTemplate.getForObject(uri, TaskMetaData.class);
                    if (taskMetaData != null) {
                        resourceMapper.map(taskMetaData.getTaskARN()).ifPresent(taskResource -> {
                            subnetDetails.set(ecsTaskUtil.getSubnetDetails(taskResource));
                            log.info("Discovered self subnet as {}", subnetDetails);
                        });
                    }
                } catch (Exception e) {
                    log.error("Failed to discover self task details", e);
                }
            } else {
                log.warn("Env variables ['ECS_CONTAINER_METADATA_URI_V4','ECS_CONTAINER_METADATA_URI'] not found");
            }
        }
    }

    @VisibleForTesting
    String getSSLFlag() {
        return System.getenv(SCRAPE_OVER_TLS);
    }

    @Builder
    @Getter
    @ToString
    @EqualsAndHashCode
    public static class StaticConfig {
        private final Set<String> targets = new TreeSet<>();
        private final Labels labels;
        @JsonIgnore
        private final Set<LogConfig> logConfigs = new HashSet<>();
    }

    @Builder
    @Getter
    @ToString
    @EqualsAndHashCode
    public static class LogConfig {
        private final String logDriver;
        private final Map<String, String> options;
    }

    @Getter
    @JsonIgnoreProperties(ignoreUnknown = true)
    @EqualsAndHashCode
    @ToString
    @SuperBuilder
    @NoArgsConstructor
    public static class TaskMetaData {
        @JsonProperty("TaskARN")
        private String taskARN;
    }
}
