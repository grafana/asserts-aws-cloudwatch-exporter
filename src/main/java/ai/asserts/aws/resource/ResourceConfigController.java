/*
 *  Copyright © 2021.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.resource;

import ai.asserts.aws.ApiAuthenticator;
import ai.asserts.aws.MetricNameUtil;
import ai.asserts.aws.ObjectMapperFactory;
import ai.asserts.aws.cloudwatch.alarms.FirehoseEventRequest;
import ai.asserts.aws.cloudwatch.alarms.RecordData;
import ai.asserts.aws.cloudwatch.config.ScrapeConfig;
import ai.asserts.aws.cloudwatch.config.ScrapeConfigProvider;
import ai.asserts.aws.exporter.BasicMetricCollector;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.google.common.annotations.VisibleForTesting;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.util.CollectionUtils;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;

import static org.springframework.http.MediaType.APPLICATION_JSON_VALUE;

@Slf4j
@AllArgsConstructor
@RestController
@SuppressWarnings("unused")
public class ResourceConfigController {
    private static final String CONFIG_CHANGE_RESOURCE = "/receive-config-change/resource";
    private static final String CONFIG_CHANGE_RESOURCE_TOKEN = "/receive-config-change/resource/{token}";
    private static final String CONFIG_CHANGE_SNS = "/receive-config-change/sns";
    private static final String CONFIG_CHANGE_SNS_TOKEN = "/receive-config-change/sns/{token}";
    private static final String CREATE_CHANGE_TYPE = "CREATE";
    private final ObjectMapperFactory objectMapperFactory;
    private final BasicMetricCollector metricCollector;
    private final ResourceMapper resourceMapper;
    private final ScrapeConfigProvider scrapeConfigProvider;
    private final ApiAuthenticator apiAuthenticator;

    @PostMapping(
            path = CONFIG_CHANGE_RESOURCE,
            produces = APPLICATION_JSON_VALUE,
            consumes = APPLICATION_JSON_VALUE)
    public ResponseEntity<Object> resourceConfigChangePost(
            @RequestBody FirehoseEventRequest resourceConfig) {
        apiAuthenticator.authenticate(Optional.empty());
        return processRequest(resourceConfig);
    }

    @PutMapping(
            path = CONFIG_CHANGE_RESOURCE,
            produces = APPLICATION_JSON_VALUE,
            consumes = APPLICATION_JSON_VALUE)
    public ResponseEntity<Object> resourceConfigChangePut(
            @RequestBody FirehoseEventRequest resourceConfig) {
        apiAuthenticator.authenticate(Optional.empty());
        return processRequest(resourceConfig);
    }

    @PostMapping(
            path = CONFIG_CHANGE_SNS,
            produces = APPLICATION_JSON_VALUE,
            consumes = APPLICATION_JSON_VALUE)
    public ResponseEntity<Object> snsConfigChangePost(
            @RequestBody Object snsConfig) {
        apiAuthenticator.authenticate(Optional.empty());
        log.info("snsConfigChange - {}", snsConfig.toString());
        return ResponseEntity.ok("Completed");
    }

    @PutMapping(
            path = CONFIG_CHANGE_SNS,
            produces = APPLICATION_JSON_VALUE,
            consumes = APPLICATION_JSON_VALUE)
    public ResponseEntity<Object> snsConfigChangePut(
            @RequestBody Object snsConfig) {
        apiAuthenticator.authenticate(Optional.empty());
        log.info("snsConfigChange - {}", snsConfig.toString());
        return ResponseEntity.ok("Completed");
    }

    @PostMapping(
            path = CONFIG_CHANGE_RESOURCE_TOKEN,
            produces = APPLICATION_JSON_VALUE,
            consumes = APPLICATION_JSON_VALUE)
    public ResponseEntity<Object> resourceConfigChangePostSecure(
            @PathVariable("token") String apiToken,
            @RequestBody FirehoseEventRequest resourceConfig) {
        apiAuthenticator.authenticate(Optional.of(apiToken));
        return processRequest(resourceConfig);
    }

    @PutMapping(
            path = CONFIG_CHANGE_RESOURCE_TOKEN,
            produces = APPLICATION_JSON_VALUE,
            consumes = APPLICATION_JSON_VALUE)
    public ResponseEntity<Object> resourceConfigChangePutSecure(
            @PathVariable("token") String apiToken,
            @RequestBody FirehoseEventRequest resourceConfig) {
        apiAuthenticator.authenticate(Optional.of(apiToken));
        return processRequest(resourceConfig);
    }

    @PostMapping(
            path = CONFIG_CHANGE_SNS_TOKEN,
            produces = APPLICATION_JSON_VALUE,
            consumes = APPLICATION_JSON_VALUE)
    public ResponseEntity<Object> snsConfigChangePostSecure(
            @PathVariable("token") String apiToken,
            @RequestBody Object snsConfig) {
        apiAuthenticator.authenticate(Optional.of(apiToken));
        log.info("snsConfigChange - {}", snsConfig.toString());
        return ResponseEntity.ok("Completed");
    }

    @PutMapping(
            path = CONFIG_CHANGE_SNS_TOKEN,
            produces = APPLICATION_JSON_VALUE,
            consumes = APPLICATION_JSON_VALUE)
    public ResponseEntity<Object> snsConfigChangePutSecure(
            @PathVariable("token") String apiToken,
            @RequestBody Object snsConfig) {
        apiAuthenticator.authenticate(Optional.of(apiToken));
        log.info("snsConfigChange - {}", snsConfig.toString());
        return ResponseEntity.ok("Completed");
    }

    private ResponseEntity<Object> processRequest(FirehoseEventRequest firehoseEventRequest) {
        try {
            if (!CollectionUtils.isEmpty(firehoseEventRequest.getRecords())) {
                for (RecordData recordData : firehoseEventRequest.getRecords()) {
                    accept(recordData);
                }
            } else {
                log.info("Unable to process alarm request-{}", firehoseEventRequest.getRequestId());
            }
        } catch (Exception ex) {
            log.error("Error in processing {}-{}", ex, ex.getStackTrace());
        }
        return ResponseEntity.ok("Completed");
    }

    private void accept(RecordData data) {
        String decodedData = new String(Base64.getDecoder().decode(data.getData()));
        try {
            ResourceConfigChange configChange = objectMapperFactory.getObjectMapper().readValue(decodedData, ResourceConfigChange.class);
            log.info("Resource {} - changeType {} - ResourceType {} - ResourceId {} ", String.join(",", configChange.getResources()),
                    configChange.getDetail().getConfigurationItemDiff().getChangeType(), configChange.getDetail().getConfigurationItem().getResourceType(),
                    configChange.getDetail().getConfigurationItem().getResourceId());
            publishConfigChange(configChange);
        } catch (JsonProcessingException jsp) {
            log.error("Error processing JSON {}-{}", decodedData, jsp.getMessage());
        }
    }

    private void publishConfigChange(ResourceConfigChange configChange) {
        ScrapeConfig scrapeConfig = scrapeConfigProvider.getScrapeConfig();
        if (!CREATE_CHANGE_TYPE.equals(configChange.getDetail().getConfigurationItemDiff().getChangeType()) &&
                scrapeConfig.getDiscoverResourceTypes().contains(configChange.getDetail().getConfigurationItem().
                        getResourceType())
                && !CollectionUtils.isEmpty(configChange.getResources())) {
            configChange.getResources().forEach(r -> {
                SortedMap<String, String> labels = new TreeMap<>();
                labels.put("region", configChange.getRegion());
                labels.put("account_id", configChange.getAccount());
                labels.put("alertname", String.format("Config-%s", configChange.getDetail().getConfigurationItemDiff().getChangeType()));
                labels.put("asserts_entity_type", "Service");
                Optional<Resource> resource = resourceMapper.map(r);
                if (resource.isPresent()) {
                    labels.put("job", resource.get().getName());
                    labels.put("namespace", resource.get().getType().getCwNamespace().getNamespace());
                    recordDelayHistogram(labels, configChange.getTime());
                    metricCollector.recordGaugeValue("aws_resource_config", labels, 1.0D);
                }
            });

        }
    }

    private void recordDelayHistogram(Map<String, String> labels, String timestamp) {
        Instant observedTime = Instant.parse(timestamp);
        SortedMap<String, String> histoLabels = new TreeMap<>();
        histoLabels.put("namespace", labels.get("namespace"));
        histoLabels.put("region", labels.get("region"));
        histoLabels.put("alertname", labels.get("alertname"));
        histoLabels.put("job", labels.get("job"));
        long diff = (now().toEpochMilli() - observedTime.toEpochMilli()) / 1000;
        this.metricCollector.recordHistogram(MetricNameUtil.EXPORTER_DELAY_SECONDS, histoLabels, diff);
    }

    @VisibleForTesting
    Instant now() {
        return Instant.now();
    }
}
