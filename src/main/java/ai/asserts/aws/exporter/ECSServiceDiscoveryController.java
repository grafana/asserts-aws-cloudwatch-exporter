/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.exporter;

import ai.asserts.aws.exporter.ECSServiceDiscoveryExporter.StaticConfig;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static org.springframework.http.MediaType.APPLICATION_JSON_VALUE;
import static org.springframework.web.bind.annotation.RequestMethod.GET;

@RestController
@AllArgsConstructor
@Slf4j
public class ECSServiceDiscoveryController {
    private final ECSTaskProvider ecsTaskProvider;

    @RequestMapping(
            path = "/ecs-sd-config",
            produces = {APPLICATION_JSON_VALUE},
            method = GET)
    public ResponseEntity<List<StaticConfig>> getECSSDConfig() {
        List<StaticConfig> targets = ecsTaskProvider.getScrapeTargets();
        return ResponseEntity.ok(targets);
    }
}
