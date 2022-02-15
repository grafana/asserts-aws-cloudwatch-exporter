/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.cloudwatch.metrics;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.http.MediaType.APPLICATION_JSON_VALUE;

@AllArgsConstructor
@RestController
@Slf4j
@Component
public class MetricStreamController {
    public static final String METRICS = "/receive-cloudwatch-metrics";

    @PostMapping(
            path = METRICS,
            produces = APPLICATION_JSON_VALUE,
            consumes = APPLICATION_JSON_VALUE)
    public ResponseEntity<MetricResponse> receiveMetricsPost(
            @RequestBody String metricRequest) {
        log.info("Cloudwatch Metric Request {}", metricRequest);
        return ResponseEntity.ok(MetricResponse.builder()
                .status("Success")
                .build());
    }

    @PutMapping(
            path = METRICS,
            produces = APPLICATION_JSON_VALUE,
            consumes = APPLICATION_JSON_VALUE)
    public ResponseEntity<MetricResponse> receiveMetricsPut(
            @RequestBody String metricRequest) {
        log.info("Cloudwatch Metric Request {}", metricRequest);
        return ResponseEntity.ok(MetricResponse.builder()
                .status("Success")
                .build());
    }
}
