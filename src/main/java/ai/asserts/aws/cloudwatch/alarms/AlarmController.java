/*
 *  Copyright © 2022.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.cloudwatch.alarms;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.http.MediaType.APPLICATION_JSON_VALUE;

@Slf4j
@RestController
@AllArgsConstructor
public class AlarmController {
    private static final String ALARMS = "/receive-cloudwatch-alarms";
    private final AlarmMetricConverter alarmMetricConverter;

    @PostMapping(
            path = ALARMS,
            produces = APPLICATION_JSON_VALUE,
            consumes = APPLICATION_JSON_VALUE)
    public ResponseEntity<AlarmResponse> receiveAlarmsPost(
            @RequestBody AlarmStateChanged alarmStateChange) {
        if (this.alarmMetricConverter.convertAlarm(alarmStateChange)) {
            return ResponseEntity.ok(AlarmResponse.builder().status("Success").build());
        }
        return ResponseEntity.unprocessableEntity().build();
    }

    @PutMapping(
            path = ALARMS,
            produces = APPLICATION_JSON_VALUE,
            consumes = APPLICATION_JSON_VALUE)
    public ResponseEntity<AlarmResponse> receiveAlarmsPut(
            @RequestBody AlarmStateChanged alarmStateChange) {
        if (this.alarmMetricConverter.convertAlarm(alarmStateChange)) {
            return ResponseEntity.ok(AlarmResponse.builder().status("Success").build());
        }
        return ResponseEntity.unprocessableEntity().build();
    }
}
