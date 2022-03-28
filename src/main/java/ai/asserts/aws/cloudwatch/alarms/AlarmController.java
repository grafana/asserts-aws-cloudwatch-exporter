/*
 *  Copyright © 2022.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.cloudwatch.alarms;

import ai.asserts.aws.ObjectMapperFactory;
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
import java.util.List;
import java.util.Map;

import static org.springframework.http.MediaType.APPLICATION_JSON_VALUE;

@Slf4j
@RestController
@AllArgsConstructor
public class AlarmController {
    private static final String ALARMS = "/receive-cloudwatch-alarms";
    private static final String ALARMS_SNS = "/receive-cloudwatch-alarms/sns";
    private final AlarmMetricConverter alarmMetricConverter;
    private final ObjectMapperFactory objectMapperFactory;
    private final AlertsProcessor alertsProcessor;

    @PostMapping(
            path = ALARMS,
            produces = APPLICATION_JSON_VALUE,
            consumes = APPLICATION_JSON_VALUE)
    public ResponseEntity<AlarmResponse> receiveAlarmsPost(
            @RequestBody FirehoseEventRequest firehoseEventRequest) {
        return processRequest(firehoseEventRequest);
    }

    @PutMapping(
            path = ALARMS,
            produces = APPLICATION_JSON_VALUE,
            consumes = APPLICATION_JSON_VALUE)
    public ResponseEntity<AlarmResponse> receiveAlarmsPut(
            @RequestBody FirehoseEventRequest firehoseEventRequest) {
        return processRequest(firehoseEventRequest);
    }

    @PostMapping(
            path = ALARMS_SNS,
            produces = APPLICATION_JSON_VALUE,
            consumes = APPLICATION_JSON_VALUE)
    public ResponseEntity<AlarmResponse> receiveAlarmSNSPost(
            @RequestBody Object alarmRequest) {
        log.info("AlarmSNS - {}",alarmRequest.toString());
        return ResponseEntity.ok(AlarmResponse.builder().status("Success").build());
    }

    @PutMapping(
            path = ALARMS_SNS,
            produces = APPLICATION_JSON_VALUE,
            consumes = APPLICATION_JSON_VALUE)
    public ResponseEntity<AlarmResponse> receiveAlarmSNSPut(
            @RequestBody Object alarmRequest) {
        log.info("AlarmSNS  - {}",alarmRequest.toString());
        return ResponseEntity.ok(AlarmResponse.builder().status("Success").build());
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
            AlarmStateChange alarmStateChange = objectMapperFactory.getObjectMapper().readValue(decodedData, AlarmStateChange.class);
            List<Map<String, String>> alarmsLabels = this.alarmMetricConverter.convertAlarm(alarmStateChange);
            if (!CollectionUtils.isEmpty(alarmsLabels)) {
                alertsProcessor.sendAlerts(alarmsLabels);
            } else {
                log.info("Unable to process alarm-{}", String.join(",", alarmStateChange.getResources()));
            }
        } catch (JsonProcessingException jsp) {
            log.error("Error processing JSON {}-{}", decodedData, jsp.getMessage());
        }
    }
}
