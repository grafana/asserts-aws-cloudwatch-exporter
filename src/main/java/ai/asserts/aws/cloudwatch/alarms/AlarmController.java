/*
 *  Copyright © 2022.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.cloudwatch.alarms;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.http.MediaType.APPLICATION_JSON_VALUE;

@Slf4j
@AllArgsConstructor
@RestController
public class AlarmController {
    private static final String ALARMS ="/receive-cloudwatch-alarms";

    @PostMapping(
            path = ALARMS,
            produces = APPLICATION_JSON_VALUE)
    public ResponseEntity<Object> receiveAlarms(
            @RequestBody Object alarmStateChange){
        log.info(alarmStateChange.toString());
        return ResponseEntity.ok("Completed");
    }
}
