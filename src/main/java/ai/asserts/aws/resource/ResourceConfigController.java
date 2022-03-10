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
    private static final String CONFIG_CHANGE_RESOURCE ="/receive-config-change/resource";
    private static final String CONFIG_CHANGE_SNS ="/receive-config-change/sns";

    @PostMapping(
            path = CONFIG_CHANGE_RESOURCE,
            produces = APPLICATION_JSON_VALUE,
            consumes = APPLICATION_JSON_VALUE)
    public ResponseEntity<Object> resourceConfigChangePost(
            @RequestBody Object resourceConfig){
        log.info("resourceConfigChange - {}",resourceConfig.toString());
        return ResponseEntity.ok("Completed");
    }

    @PutMapping(
            path = CONFIG_CHANGE_RESOURCE,
            produces = APPLICATION_JSON_VALUE,
            consumes = APPLICATION_JSON_VALUE)
    public ResponseEntity<Object> resourceConfigChangePut(
            @RequestBody Object resourceConfig){
        log.info("resourceConfigChange - {}",resourceConfig.toString());
        return ResponseEntity.ok("Completed");
    }

    @PostMapping(
            path = CONFIG_CHANGE_SNS,
            produces = APPLICATION_JSON_VALUE,
            consumes = APPLICATION_JSON_VALUE)
    public ResponseEntity<Object> snsConfigChangePost(
            @RequestBody Object snsConfig){
        log.info("snsConfigChange - {}",snsConfig.toString());
        return ResponseEntity.ok("Completed");
    }

    @PutMapping(
            path = CONFIG_CHANGE_SNS,
            produces = APPLICATION_JSON_VALUE,
            consumes = APPLICATION_JSON_VALUE)
    public ResponseEntity<Object> snsConfigChangePut(
            @RequestBody Object snsConfig){
        log.info("snsConfigChange - {}",snsConfig.toString());
        return ResponseEntity.ok("Completed");
    }
}
