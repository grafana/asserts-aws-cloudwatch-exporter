/*
 *  Copyright © 2022.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.resource;

import ai.asserts.aws.ApiAuthenticator;
import ai.asserts.aws.MetricNameUtil;
import ai.asserts.aws.ObjectMapperFactory;
import ai.asserts.aws.cloudwatch.alarms.FirehoseEventRequest;
import ai.asserts.aws.cloudwatch.alarms.RecordData;
import ai.asserts.aws.config.DimensionToLabel;
import ai.asserts.aws.config.ScrapeConfig;
import ai.asserts.aws.ScrapeConfigProvider;
import ai.asserts.aws.exporter.BasicMetricCollector;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import org.easymock.EasyMockSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;

import static org.easymock.EasyMock.expect;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class ResourceConfigControllerTest extends EasyMockSupport {

    private ObjectMapperFactory objectMapperFactory;
    private BasicMetricCollector metricCollector;
    private ResourceMapper resourceMapper;
    private ScrapeConfigProvider scrapeConfigProvider;
    private ObjectMapper objectMapper;
    private ScrapeConfig scrapeConfig;
    private RecordData recordData;
    private ResourceConfigController testClass;
    private FirehoseEventRequest resourceConfig;
    private ResourceConfigChange configChange;
    private ResourceConfigChangeDetail changeDetail;
    private ResourceConfigDiff configDiff;
    private ResourceConfigItem configItem;
    private DimensionToLabel dimensionToLabel;
    private ResourceChangedItem changedItem;
    private ApiAuthenticator apiAuthenticator;
    private Instant now;

    @BeforeEach
    public void setup() {
        objectMapperFactory = mock(ObjectMapperFactory.class);
        metricCollector = mock(BasicMetricCollector.class);
        resourceMapper = mock(ResourceMapper.class);
        scrapeConfigProvider = mock(ScrapeConfigProvider.class);
        objectMapper = mock(ObjectMapper.class);
        scrapeConfig = mock(ScrapeConfig.class);
        resourceConfig = mock(FirehoseEventRequest.class);
        recordData = mock(RecordData.class);
        configChange = mock(ResourceConfigChange.class);
        changeDetail = mock(ResourceConfigChangeDetail.class);
        configDiff = mock(ResourceConfigDiff.class);
        configItem = mock(ResourceConfigItem.class);
        dimensionToLabel = mock(DimensionToLabel.class);
        changedItem = mock(ResourceChangedItem.class);
        apiAuthenticator = mock(ApiAuthenticator.class);
        now = Instant.now();
        testClass = new ResourceConfigController(objectMapperFactory, metricCollector, resourceMapper,
                scrapeConfigProvider, apiAuthenticator) {
            @Override
            Instant now() {
                return now;
            }
        };
        expect(objectMapperFactory.getObjectMapper()).andReturn(objectMapper);
        expect(scrapeConfigProvider.getScrapeConfig()).andReturn(scrapeConfig).times(2);
        expect(resourceConfig.getRecords()).andReturn(ImmutableList.of(recordData)).times(2);
        expect(configChange.getDetail()).andReturn(changeDetail).anyTimes();
        expect(changeDetail.getConfigurationItemDiff()).andReturn(configDiff).times(4);
        expect(changeDetail.getConfigurationItem()).andReturn(configItem).times(3);
    }

    @Test
    public void resourceConfigChangePost() throws JsonProcessingException {
        Resource resource = mock(Resource.class);
        expect(scrapeConfig.getDiscoverResourceTypes()).andReturn(ImmutableSet.of("AWS::EC2::Instance"));
        expect(scrapeConfig.getDimensionToLabels()).andReturn(ImmutableList.of(dimensionToLabel));
        expect(dimensionToLabel.getNamespace()).andReturn("AWS/EC2");
        expect(dimensionToLabel.getMapToLabel()).andReturn(null);
        expect(dimensionToLabel.getEntityType()).andReturn(null);
        expect(recordData.getData()).andReturn(Base64.getEncoder().encodeToString("test".getBytes()));
        expect(objectMapper.readValue("test", ResourceConfigChange.class)).andReturn(configChange);
        expect(configDiff.getChangeType()).andReturn("UPDATE").times(3);
        expect(configDiff.getChangedProperties()).andReturn(ImmutableMap.of("ServiceConfig", changedItem)).times(2);
        expect(changedItem.getChangeType()).andReturn("UPDATE");
        expect(configItem.getResourceType()).andReturn("AWS::EC2::Instance").times(2);
        expect(configItem.getResourceId()).andReturn("i-04ac60054729e1e1f");
        expect(configChange.getRegion()).andReturn("r1");
        expect(configChange.getAccount()).andReturn("123");
        expect(configChange.getTime()).andReturn(now.minusSeconds(5).toString());
        expect(configChange.getResources()).
                andReturn(ImmutableList.of("arn:aws:ec2:us-west-2:342994379019:instance/i-04ac60054729e1e1f")).times(3);
        expect(resourceMapper.map("arn:aws:ec2:us-west-2:342994379019:instance/i-04ac60054729e1e1f")).
                andReturn(Optional.of(resource));
        expect(resource.getName()).andReturn("i-04ac60054729e1e1f");
        expect(resource.getType()).andReturn(ResourceType.EC2Instance);
        SortedMap<String, String> labels = new TreeMap<>();
        labels.put("account_id", "123");
        labels.put("change", "AWSResourceConfig-Update");
        labels.put("asserts_entity_type", "Service");
        labels.put("job", "i-04ac60054729e1e1f");
        labels.put("namespace", "AWS/EC2");
        labels.put("region", "r1");
        metricCollector.recordGaugeValue("aws_resource_config", labels, 1.0D);
        metricCollector.recordHistogram(MetricNameUtil.EXPORTER_DELAY_SECONDS, labels, 5);
        apiAuthenticator.authenticate(Optional.empty());
        replayAll();
        assertEquals(HttpStatus.OK, testClass.resourceConfigChangePost(resourceConfig).getStatusCode());
        verifyAll();
    }

    @Test
    public void resourceConfigChangePostSecure() throws JsonProcessingException {
        Resource resource = mock(Resource.class);
        expect(scrapeConfig.getDiscoverResourceTypes()).andReturn(ImmutableSet.of("AWS::EC2::Instance"));
        expect(scrapeConfig.getDimensionToLabels()).andReturn(ImmutableList.of(dimensionToLabel));
        expect(dimensionToLabel.getNamespace()).andReturn("AWS/EC2");
        expect(dimensionToLabel.getMapToLabel()).andReturn("workload").times(2);
        expect(dimensionToLabel.getEntityType()).andReturn(null);
        expect(recordData.getData()).andReturn(Base64.getEncoder().encodeToString("test".getBytes()));
        expect(objectMapper.readValue("test", ResourceConfigChange.class)).andReturn(configChange);
        expect(configDiff.getChangeType()).andReturn("UPDATE").times(3);
        expect(configDiff.getChangedProperties()).andReturn(ImmutableMap.of("ServiceConfig", changedItem)).times(2);
        expect(changedItem.getChangeType()).andReturn("UPDATE");
        expect(configItem.getResourceType()).andReturn("AWS::EC2::Instance").times(2);
        expect(configItem.getResourceId()).andReturn("i-04ac60054729e1e1f");
        expect(configChange.getRegion()).andReturn("r1");
        expect(configChange.getAccount()).andReturn("123");
        expect(configChange.getTime()).andReturn(now.minusSeconds(5).toString());
        expect(configChange.getResources()).
                andReturn(ImmutableList.of("arn:aws:ec2:us-west-2:342994379019:instance/i-04ac60054729e1e1f")).times(3);
        expect(resourceMapper.map("arn:aws:ec2:us-west-2:342994379019:instance/i-04ac60054729e1e1f")).
                andReturn(Optional.of(resource));
        expect(resource.getName()).andReturn("i-04ac60054729e1e1f");
        expect(resource.getType()).andReturn(ResourceType.EC2Instance);
        SortedMap<String, String> labels = new TreeMap<>();
        labels.put("account_id", "123");
        labels.put("change", "AWSResourceConfig-Update");
        labels.put("asserts_entity_type", "Service");
        labels.put("workload", "i-04ac60054729e1e1f");
        labels.put("namespace", "AWS/EC2");
        labels.put("region", "r1");
        metricCollector.recordGaugeValue("aws_resource_config", labels, 1.0D);
        metricCollector.recordHistogram(MetricNameUtil.EXPORTER_DELAY_SECONDS, labels, 5);
        apiAuthenticator.authenticate(Optional.of("token"));
        replayAll();
        assertEquals(HttpStatus.OK, testClass.resourceConfigChangePostSecure("token",
                resourceConfig).getStatusCode());
        verifyAll();
    }
}
