/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.exporter;

import ai.asserts.aws.AccountProvider;
import ai.asserts.aws.ObjectMapperFactory;
import ai.asserts.aws.ScrapeConfigProvider;
import ai.asserts.aws.config.ScrapeConfig;
import ai.asserts.aws.config.ScrapeConfig.SubnetDetails;
import ai.asserts.aws.resource.ResourceMapper;
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
import java.util.Arrays;
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
    private final RestTemplate restTemplate;
    private final AccountProvider accountProvider;
    private final ScrapeConfigProvider scrapeConfigProvider;
    private final ResourceMapper resourceMapper;
    private final ECSTaskUtil ecsTaskUtil;
    private final ObjectMapperFactory objectMapperFactory;

    private final ECSTaskProvider ecsTaskProvider;

    @Getter
    private final AtomicReference<SubnetDetails> subnetDetails = new AtomicReference<>(null);

    @Getter
    protected final Set<String> subnetsToScrape = new TreeSet<>();

    public ECSServiceDiscoveryExporter(RestTemplate restTemplate, AccountProvider accountProvider,
                                       ScrapeConfigProvider scrapeConfigProvider, ResourceMapper resourceMapper,
                                       ECSTaskUtil ecsTaskUtil, ObjectMapperFactory objectMapperFactory,
                                       ECSTaskProvider ecsTaskProvider) {
        this.restTemplate = restTemplate;
        this.accountProvider = accountProvider;
        this.scrapeConfigProvider = scrapeConfigProvider;
        this.resourceMapper = resourceMapper;
        this.ecsTaskUtil = ecsTaskUtil;
        this.objectMapperFactory = objectMapperFactory;
        this.ecsTaskProvider = ecsTaskProvider;
        identifySubnetsToScrape();
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
        ClassPathResource classPathResource = new ClassPathResource("/dummy-ecs-targets.yml");
        File out = new File(scrapeConfigProvider.getScrapeConfig().getEcsTargetSDFile());
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
        ScrapeConfig scrapeConfig = scrapeConfigProvider.getScrapeConfig();
        Map<String, SubnetDetails> primaryExportersByAccount = scrapeConfig.getPrimaryExporterByAccount();
        SubnetDetails primaryConfig = primaryExportersByAccount.get(accountProvider.getCurrentAccountId());
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
        ScrapeConfig scrapeConfig = scrapeConfigProvider.getScrapeConfig();
        if (scrapeConfig.isDiscoverECSTasks()) {
            List<StaticConfig> targets = ecsTaskProvider.getScrapeTargets();
            try {
                File resultFile = new File(scrapeConfig.getEcsTargetSDFile());
                ObjectWriter objectWriter = objectMapperFactory.getObjectMapper().writerWithDefaultPrettyPrinter();
                objectWriter.writeValue(resultFile, targets);
                if (scrapeConfig.isLogECSTargets()) {
                    String targetsFileContent = objectWriter.writeValueAsString(targets);
                    log.info("Wrote ECS scrape target SD file {}\n{}\n", resultFile.toURI(), targetsFileContent);
                } else {
                    log.info("Wrote ECS scrape target SD file {}", resultFile.toURI());
                }
            } catch (IOException e) {
                log.error("Failed to write ECS SD file", e);
            }
        }
    }

    @VisibleForTesting
    boolean shouldScrapeTargets(ScrapeConfig scrapeConfig, StaticConfig config) {
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

    @Builder
    @Getter
    public static class StaticConfig {
        private final Set<String> targets = new TreeSet<>();
        private final Labels labels;
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
