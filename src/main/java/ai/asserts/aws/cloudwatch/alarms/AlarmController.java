/*
 *  Copyright © 2022.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.cloudwatch.alarms;

import io.prometheus.client.CollectorRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.http.MediaType.APPLICATION_JSON_VALUE;

@Slf4j
@RestController
public class AlarmController {
    private static final String ALARMS ="/receive-cloudwatch-alarms";

    @PostMapping(
            path = ALARMS,
            produces = APPLICATION_JSON_VALUE)
    public ResponseEntity<AlarmResponse> receiveAlarmsPost(
            @RequestBody Object alarmStateChange){
        log.info(alarmStateChange.toString());
        return ResponseEntity.ok(AlarmResponse.builder().status("Success").build());
    }

    @PutMapping(
            path = ALARMS,
            produces = APPLICATION_JSON_VALUE)
    public ResponseEntity<AlarmResponse> receiveAlarmsPut(
            @RequestBody Object alarmStateChange){
        log.info(alarmStateChange.toString());
        return ResponseEntity.ok(AlarmResponse.builder().status("Success").build());
    }
}
