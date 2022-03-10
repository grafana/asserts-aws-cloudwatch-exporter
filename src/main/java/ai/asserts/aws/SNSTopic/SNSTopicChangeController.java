/*
 *  Copyright © 2022.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.SNSTopic;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.http.MediaType.APPLICATION_JSON_VALUE;

@Slf4j
@AllArgsConstructor
@RestController
public class SNSTopicChangeController {
    private static final String SNS_TOPIC_EVENTS ="/receive-sns-topic-events";

    @PostMapping(
            path = SNS_TOPIC_EVENTS,
            produces = APPLICATION_JSON_VALUE,
            consumes = APPLICATION_JSON_VALUE)
    public ResponseEntity<Object> receiveSNSTopicEventsPost(
            @RequestBody Object configEvents){
        log.info(configEvents.toString());
        return ResponseEntity.ok("Completed");
    }

    @PutMapping(
            path = SNS_TOPIC_EVENTS,
            produces = APPLICATION_JSON_VALUE,
            consumes = APPLICATION_JSON_VALUE)
    public ResponseEntity<Object> receiveSNSTopicEventsPut(
            @RequestBody Object configEvents){
        log.info(configEvents.toString());
        return ResponseEntity.ok("Completed");
    }
}
