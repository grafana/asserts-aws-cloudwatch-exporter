/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.http.MediaType.TEXT_PLAIN_VALUE;

@Slf4j
@RestController
@SuppressWarnings("unused")
public class HealthCheckController {
    @GetMapping(
            path = "/health-check",
            produces = {TEXT_PLAIN_VALUE}
    )
    public ResponseEntity<String> healthCheck() {
        return ResponseEntity.ok("Healthy!");
    }
}
