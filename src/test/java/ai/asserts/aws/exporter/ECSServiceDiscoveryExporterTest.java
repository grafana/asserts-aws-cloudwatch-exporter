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
import ai.asserts.aws.exporter.ECSServiceDiscoveryExporter.StaticConfig;
import ai.asserts.aws.exporter.ECSServiceDiscoveryExporter.TaskMetaData;
import ai.asserts.aws.resource.Resource;
import ai.asserts.aws.resource.ResourceMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.easymock.EasyMockSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestTemplate;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static ai.asserts.aws.exporter.ECSServiceDiscoveryExporter.SD_FILE_PATH;
import static ai.asserts.aws.exporter.ECSServiceDiscoveryExporter.SD_FILE_PATH_SECURE;
import static org.easymock.EasyMock.anyObject;
import static org.easymock.EasyMock.eq;
import static org.easymock.EasyMock.expect;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ECSServiceDiscoveryExporterTest extends EasyMockSupport {
    private RestTemplate restTemplate;
    private AccountProvider accountProvider;
    private ScrapeConfigProvider scrapeConfigProvider;
    private ScrapeConfig scrapeConfig;
    private ResourceMapper resourceMapper;
    private Resource resource;
    private ECSTaskUtil ecsTaskUtil;
    private ObjectMapperFactory objectMapperFactory;
    private ObjectMapper objectMapper;
    private ObjectWriter objectWriter;
    private StaticConfig mockStaticConfig;
    private Labels mockLabels;
    private ECSTaskProvider ecsTaskProvider;

    @BeforeEach
    public void setup() {
        restTemplate = mock(RestTemplate.class);
        accountProvider = mock(AccountProvider.class);
        scrapeConfigProvider = mock(ScrapeConfigProvider.class);
        scrapeConfig = mock(ScrapeConfig.class);
        resourceMapper = mock(ResourceMapper.class);
        resource = mock(Resource.class);
        ecsTaskUtil = mock(ECSTaskUtil.class);
        objectMapperFactory = mock(ObjectMapperFactory.class);
        objectMapper = mock(ObjectMapper.class);
        objectWriter = mock(ObjectWriter.class);
        mockStaticConfig = mock(StaticConfig.class);
        mockLabels = mock(Labels.class);
        ecsTaskProvider = mock(ECSTaskProvider.class);
        resetAll();
    }

    @Test
    public void discoverSubnet() {
        expect(restTemplate.getForObject(anyObject(), anyObject())).andReturn(TaskMetaData.builder()
                .taskARN("self-task-arn")
                .build());
        expect(resourceMapper.map("self-task-arn")).andReturn(Optional.of(resource));
        expect(ecsTaskUtil.getSubnetDetails(resource)).andReturn(SubnetDetails.builder()
                .subnetId("subnet-id")
                .vpcId("vpc-id")
                .build());
        replayAll();
        ECSServiceDiscoveryExporter testClass = new ECSServiceDiscoveryExporter(
                restTemplate, accountProvider, scrapeConfigProvider, resourceMapper, ecsTaskUtil, objectMapperFactory,
                ecsTaskProvider) {
            @Override
            String getMetaDataURI() {
                return "http://localhost";
            }
        };
        testClass.discoverSelfSubnet();
        assertEquals(SubnetDetails.builder()
                .vpcId("vpc-id")
                .subnetId("subnet-id")
                .build(), testClass.getSubnetDetails().get());
        verifyAll();
    }

    @Test
    public void isPrimary_primaryVpcSubnetNotSpecified() {
        expect(accountProvider.getCurrentAccountId()).andReturn("account-id");
        expect(scrapeConfigProvider.getScrapeConfig()).andReturn(scrapeConfig).anyTimes();
        expect(scrapeConfig.getPrimaryExporterByAccount()).andReturn(ImmutableMap.of()).anyTimes();
        replayAll();
        ECSServiceDiscoveryExporter testClass = new ECSServiceDiscoveryExporter(
                restTemplate, accountProvider, scrapeConfigProvider,
                resourceMapper, ecsTaskUtil, objectMapperFactory, ecsTaskProvider);

        testClass.getSubnetDetails().set(SubnetDetails.builder()
                .vpcId("vpc-id")
                .subnetId("subnet-id")
                .build());
        assertTrue(testClass.isPrimaryExporter());
        verifyAll();
    }

    @Test
    public void isPrimary_primaryVpcMatches() {
        expect(accountProvider.getCurrentAccountId()).andReturn("account-id");
        expect(scrapeConfigProvider.getScrapeConfig()).andReturn(scrapeConfig).anyTimes();
        expect(scrapeConfig.getPrimaryExporterByAccount()).andReturn(ImmutableMap.of("account-id",
                SubnetDetails.builder().vpcId("vpc-id").build())).anyTimes();
        replayAll();
        ECSServiceDiscoveryExporter testClass = new ECSServiceDiscoveryExporter(
                restTemplate, accountProvider, scrapeConfigProvider,
                resourceMapper, ecsTaskUtil, objectMapperFactory, ecsTaskProvider);

        testClass.getSubnetDetails().set(SubnetDetails.builder()
                .vpcId("vpc-id")
                .subnetId("subnet-id")
                .build());
        assertTrue(testClass.isPrimaryExporter());
        verifyAll();
    }

    @Test
    public void isPrimary_subnetMatches() {
        expect(accountProvider.getCurrentAccountId()).andReturn("account-id");
        expect(scrapeConfigProvider.getScrapeConfig()).andReturn(scrapeConfig).anyTimes();
        expect(scrapeConfig.getPrimaryExporterByAccount()).andReturn(ImmutableMap.of("account-id",
                SubnetDetails.builder().subnetId("subnet-id").build())).anyTimes();
        replayAll();
        ECSServiceDiscoveryExporter testClass = new ECSServiceDiscoveryExporter(
                restTemplate, accountProvider, scrapeConfigProvider,
                resourceMapper, ecsTaskUtil, objectMapperFactory, ecsTaskProvider);

        testClass.getSubnetDetails().set(SubnetDetails.builder()
                .vpcId("vpc-id")
                .subnetId("subnet-id")
                .build());
        assertTrue(testClass.isPrimaryExporter());
        verifyAll();
    }

    @Test
    public void isPrimary_primaryVpcDoesNotMatch() {
        expect(accountProvider.getCurrentAccountId()).andReturn("account-id");
        expect(scrapeConfigProvider.getScrapeConfig()).andReturn(scrapeConfig).anyTimes();
        expect(scrapeConfig.getPrimaryExporterByAccount()).andReturn(ImmutableMap.of("account-id",
                SubnetDetails.builder().vpcId("vpc-id").build())).anyTimes();
        replayAll();
        ECSServiceDiscoveryExporter testClass = new ECSServiceDiscoveryExporter(
                restTemplate, accountProvider, scrapeConfigProvider,
                resourceMapper, ecsTaskUtil, objectMapperFactory, ecsTaskProvider);

        testClass.getSubnetDetails().set(SubnetDetails.builder()
                .vpcId("vpc-id1")
                .subnetId("subnet-id")
                .build());
        assertFalse(testClass.isPrimaryExporter());
        verifyAll();
    }

    @Test
    public void isPrimary_subnetDoesNotMatch() {
        expect(scrapeConfigProvider.getScrapeConfig()).andReturn(scrapeConfig).anyTimes();
        expect(accountProvider.getCurrentAccountId()).andReturn("account-id");
        expect(scrapeConfig.getPrimaryExporterByAccount()).andReturn(ImmutableMap.of("account-id",
                SubnetDetails.builder().subnetId("subnet-id").build())).anyTimes();
        replayAll();
        ECSServiceDiscoveryExporter testClass = new ECSServiceDiscoveryExporter(
                restTemplate, accountProvider, scrapeConfigProvider,
                resourceMapper, ecsTaskUtil, objectMapperFactory, ecsTaskProvider);

        testClass.getSubnetDetails().set(SubnetDetails.builder()
                .vpcId("vpc-id")
                .subnetId("subnet-id1")
                .build());
        assertFalse(testClass.isPrimaryExporter());
        verifyAll();
    }

    @Test
    public void shouldScrapeTargets() {
        expect(scrapeConfigProvider.getScrapeConfig()).andReturn(scrapeConfig).anyTimes();
        expect(scrapeConfig.isDiscoverECSTasksAcrossVPCs()).andReturn(false).anyTimes();
        expect(scrapeConfig.isDiscoverOnlySubnetTasks()).andReturn(true).anyTimes();

        replayAll();
        ECSServiceDiscoveryExporter testClass = new ECSServiceDiscoveryExporter(
                restTemplate, accountProvider, scrapeConfigProvider,
                resourceMapper, ecsTaskUtil, objectMapperFactory, ecsTaskProvider) {
            @Override
            void identifySubnetsToScrape() {
                super.subnetsToScrape.add("subnet-1");
                super.subnetsToScrape.add("subnet-2");
            }
        };

        // Same VPC and Subnet
        testClass.getSubnetDetails().set(SubnetDetails.builder()
                .vpcId("vpc-1")
                .subnetId("subnet-1")
                .build());
        assertTrue(testClass.shouldScrapeTargets(scrapeConfig, StaticConfig.builder()
                .labels(Labels.builder()
                        .vpcId("vpc-1")
                        .subnetId("subnet-1")
                        .build())
                .build()));

        // Same VPC, different subnet. But subnet configured to be scraped
        assertTrue(testClass.shouldScrapeTargets(scrapeConfig, StaticConfig.builder()
                .labels(Labels.builder()
                        .vpcId("vpc-1")
                        .subnetId("subnet-2")
                        .build())
                .build()));

        // Same VPC, different subnet. But subnet not configured to be scraped
        assertFalse(testClass.shouldScrapeTargets(scrapeConfig, StaticConfig.builder()
                .labels(Labels.builder()
                        .vpcId("vpc-1")
                        .subnetId("subnet-3")
                        .build())
                .build()));

        // Different VPC
        assertFalse(testClass.shouldScrapeTargets(scrapeConfig, StaticConfig.builder()
                .labels(Labels.builder()
                        .vpcId("vpc-2")
                        .subnetId("subnet-1")
                        .build())
                .build()));
        verifyAll();
    }

    @Test
    public void run() throws Exception {
        expect(scrapeConfigProvider.getScrapeConfig()).andReturn(scrapeConfig);
        expect(scrapeConfig.isDiscoverECSTasks()).andReturn(true);

        expect(ecsTaskProvider.getScrapeTargets()).andReturn(ImmutableList.of(mockStaticConfig, mockStaticConfig));

        expect(scrapeConfig.isLogECSTargets()).andReturn(true);
        expect(mockStaticConfig.getLabels()).andReturn(mockLabels).anyTimes();
        expect(mockLabels.getVpcId()).andReturn("vpc-id").anyTimes();
        expect(mockLabels.getSubnetId()).andReturn("subnet-id").anyTimes();


        expect(objectMapperFactory.getObjectMapper()).andReturn(objectMapper);
        expect(objectMapper.writerWithDefaultPrettyPrinter()).andReturn(objectWriter);

        objectWriter.writeValue(anyObject(File.class), eq(ImmutableList.of(
                mockStaticConfig, mockStaticConfig
        )));

        expect(objectWriter.writeValueAsString(eq(ImmutableList.of(
                mockStaticConfig, mockStaticConfig
        )))).andReturn("content");

        replayAll();
        ECSServiceDiscoveryExporter testClass = new ECSServiceDiscoveryExporter(
                restTemplate, accountProvider, scrapeConfigProvider,
                resourceMapper, ecsTaskUtil, objectMapperFactory, ecsTaskProvider);
        testClass.getSubnetDetails().set(SubnetDetails.builder().vpcId("vpc-id").subnetId("subnet-id").build());
        testClass.run();

        verifyAll();
    }

    @Test
    public void runTLSEnabled() {
        expect(scrapeConfigProvider.getScrapeConfig()).andReturn(scrapeConfig);
        expect(scrapeConfig.isDiscoverECSTasks()).andReturn(true);

        StaticConfig exporterContainer = mock(StaticConfig.class);
        expect(ecsTaskProvider.getScrapeTargets()).andReturn(ImmutableList.of(
                mockStaticConfig, mockStaticConfig, exporterContainer));

        expect(mockStaticConfig.getLabels()).andReturn(mockLabels).anyTimes();
        expect(exporterContainer.getLabels()).andReturn(mockLabels);
        expect(mockLabels.getContainer()).andReturn("some-container").times(2);
        expect(mockLabels.getContainer()).andReturn("cloudwatch-exporter");
        expect(mockLabels.getVpcId()).andReturn("vpc-id").anyTimes();
        expect(mockLabels.getSubnetId()).andReturn("subnet-id").anyTimes();

        replayAll();

        Map<String, List<StaticConfig>> writes = new HashMap<>();
        ECSServiceDiscoveryExporter testClass = new ECSServiceDiscoveryExporter(
                restTemplate, accountProvider, scrapeConfigProvider,
                resourceMapper, ecsTaskUtil, objectMapperFactory, ecsTaskProvider) {
            @Override
            void writeFile(ScrapeConfig scrapeConfig, List<StaticConfig> targets, String filePath) {
                writes.put(filePath, targets);
            }

            @Override
            String getSSLFlag() {
                return "true";
            }
        };
        testClass.getSubnetDetails().set(SubnetDetails.builder().vpcId("vpc-id").subnetId("subnet-id").build());
        testClass.run();

        assertEquals(2, writes.size());
        assertTrue(writes.containsKey(SD_FILE_PATH));
        assertEquals(ImmutableList.of(exporterContainer), writes.get(SD_FILE_PATH));
        assertTrue(writes.containsKey(SD_FILE_PATH_SECURE));
        assertEquals(ImmutableList.of(mockStaticConfig, mockStaticConfig), writes.get(SD_FILE_PATH_SECURE));
        verifyAll();
    }
}
