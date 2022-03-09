/*
 *  Copyright © 2021.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.resource;

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
public class ResourceConfigController {
    private static final String CONFIG_EVENTS ="/receive-config-events";

    @PostMapping(
            path = CONFIG_EVENTS,
            produces = APPLICATION_JSON_VALUE,
            consumes = APPLICATION_JSON_VALUE)
    public ResponseEntity<Object> receiveConfigEventsPost(
            @RequestBody Object configEvents){
        log.info(configEvents.toString());
        return ResponseEntity.ok("Completed");
    }

    @PutMapping(
            path = CONFIG_EVENTS,
            produces = APPLICATION_JSON_VALUE,
            consumes = APPLICATION_JSON_VALUE)
    public ResponseEntity<Object> receiveConfigEventsPut(
            @RequestBody Object configEvents){
        log.info(configEvents.toString());
        return ResponseEntity.ok("Completed");
    }
}
