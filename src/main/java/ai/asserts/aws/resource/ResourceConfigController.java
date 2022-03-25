/*
 *  Copyright © 2021.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.resource;

import ai.asserts.aws.ObjectMapperFactory;
import ai.asserts.aws.cloudwatch.alarms.AlarmResponse;
import ai.asserts.aws.cloudwatch.alarms.FirehoseEventRequest;
import ai.asserts.aws.cloudwatch.alarms.RecordData;
import com.fasterxml.jackson.core.JsonProcessingException;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.util.CollectionUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Base64;

import static org.springframework.http.MediaType.APPLICATION_JSON_VALUE;

@Slf4j
@AllArgsConstructor
@RestController
public class ResourceConfigController {
    private static final String CONFIG_CHANGE_RESOURCE = "/receive-config-change/resource";
    private static final String CONFIG_CHANGE_SNS = "/receive-config-change/sns";
    private final ObjectMapperFactory objectMapperFactory;

    @PostMapping(
            path = CONFIG_CHANGE_RESOURCE,
            produces = APPLICATION_JSON_VALUE,
            consumes = APPLICATION_JSON_VALUE)
    public ResponseEntity<Object> resourceConfigChangePost(
            @RequestBody FirehoseEventRequest resourceConfig) {
        processRequest(resourceConfig);
        return ResponseEntity.ok("Completed");
    }

    @PutMapping(
            path = CONFIG_CHANGE_RESOURCE,
            produces = APPLICATION_JSON_VALUE,
            consumes = APPLICATION_JSON_VALUE)
    public ResponseEntity<Object> resourceConfigChangePut(
            @RequestBody FirehoseEventRequest resourceConfig) {
        processRequest(resourceConfig);
        return ResponseEntity.ok("Completed");
    }

    @PostMapping(
            path = CONFIG_CHANGE_SNS,
            produces = APPLICATION_JSON_VALUE,
            consumes = APPLICATION_JSON_VALUE)
    public ResponseEntity<Object> snsConfigChangePost(
            @RequestBody Object snsConfig) {
        log.info("snsConfigChange - {}", snsConfig.toString());
        return ResponseEntity.ok("Completed");
    }

    @PutMapping(
            path = CONFIG_CHANGE_SNS,
            produces = APPLICATION_JSON_VALUE,
            consumes = APPLICATION_JSON_VALUE)
    public ResponseEntity<Object> snsConfigChangePut(
            @RequestBody Object snsConfig) {
        log.info("snsConfigChange - {}", snsConfig.toString());
        return ResponseEntity.ok("Completed");
    }

    private ResponseEntity<AlarmResponse> processRequest(FirehoseEventRequest firehoseEventRequest) {
        try {
            if (!CollectionUtils.isEmpty(firehoseEventRequest.getRecords())) {
                for (RecordData recordData : firehoseEventRequest.getRecords()) {
                    accept(recordData);
                }
            } else {
                log.info("Unable to process alarm request-{}", firehoseEventRequest.getRequestId());
            }
        } catch (Exception ex) {
            log.error("Error in processing {}-{}", ex.toString(), ex.getStackTrace());
        }
        return ResponseEntity.ok(AlarmResponse.builder().status("Success").build());
    }

    private void accept(RecordData data) {
        String decodedData = new String(Base64.getDecoder().decode(data.getData()));
        try {
            ResourceConfigChange configChange = objectMapperFactory.getObjectMapper().readValue(decodedData, ResourceConfigChange.class);
            log.info("Resource {} - changeType {} - ResourceType {} - ResourceId {} ", String.join(",", configChange.getResources()),
                    configChange.getDetail().getConfigurationItemDiff().getChangeType(), configChange.getDetail().getConfigurationItem().getResourceType(),
                    configChange.getDetail().getConfigurationItem().getResourceId());
        } catch (JsonProcessingException jsp) {
            log.error("Error processing JSON {}-{}", decodedData, jsp.getMessage());
        }
    }
}
