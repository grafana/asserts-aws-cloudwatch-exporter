/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.cloudwatch.metrics;

import ai.asserts.aws.ObjectMapperFactory;
import ai.asserts.aws.cloudwatch.alarms.FirehoseEventRequest;
import ai.asserts.aws.cloudwatch.alarms.RecordData;
import com.fasterxml.jackson.core.JsonProcessingException;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import static org.springframework.http.MediaType.APPLICATION_JSON_VALUE;

@AllArgsConstructor
@RestController
@Slf4j
@Component
public class MetricStreamController {
    public static final String METRICS = "/receive-cloudwatch-metrics";
    private final ObjectMapperFactory objectMapperFactory;
    private final CloudWatchMetricExporter exporter;

    @PostMapping(
            path = METRICS,
            produces = APPLICATION_JSON_VALUE,
            consumes = APPLICATION_JSON_VALUE)
    public ResponseEntity<MetricResponse> receiveMetricsPost(
            @RequestBody FirehoseEventRequest metricRequest) {
        processRequest(metricRequest);
        return ResponseEntity.ok(MetricResponse.builder()
                .status("Success")
                .build());
    }

    @PutMapping(
            path = METRICS,
            produces = APPLICATION_JSON_VALUE,
            consumes = APPLICATION_JSON_VALUE)
    public ResponseEntity<MetricResponse> receiveMetricsPut(
            @RequestBody FirehoseEventRequest metricRequest) {
        processRequest(metricRequest);
        return ResponseEntity.ok(MetricResponse.builder()
                .status("Success")
                .build());
    }

    private void processRequest(FirehoseEventRequest firehoseEventRequest) {
        try {
            if (!CollectionUtils.isEmpty(firehoseEventRequest.getRecords())) {
                for (RecordData recordData : firehoseEventRequest.getRecords()) {
                    accept(recordData);
                }
            } else {
                log.info("Unable to process Cloudwatch metric request-{}", firehoseEventRequest.getRequestId());
            }
        } catch (Exception ex) {
            log.error("Error in processing {}-{}", ex.toString(), ex.getStackTrace());
        }
    }

    private void accept(RecordData data) {
        String decodedData = new String(Base64.getDecoder().decode(data.getData()));
        decodedData = decodedData.replace("}\n{", "},{");
        decodedData = "{\"metrics\":[" + decodedData + "]}";
        try {
            CloudWatchMetrics metrics = objectMapperFactory.getObjectMapper().readValue(decodedData, CloudWatchMetrics.class);
            metrics.getMetrics().forEach(m -> {
                exporter.addMetric(convertMetric(m));
                log.info("Metric Name{} - Namespace {}", m.getMetric_name(), m.getNamespace());
            });
        } catch (JsonProcessingException jsp) {
            log.error("Error processing JSON {}-{}", decodedData, jsp.getMessage());
        }
    }

    private Map<String, String> convertMetric(CloudWatchMetric metric) {
        Map<String, String> metricMap = new HashMap<>();
        metricMap.put("metric_name", metric.getMetric_name());
        metricMap.put("namespace", metric.getNamespace());
        metricMap.put("region", metric.getRegion());
        metricMap.put("account_id", metric.getAccount_id());
        metricMap.put("unit", metric.getUnit());
        if (!CollectionUtils.isEmpty(metric.getDimensions())) {
            metricMap.putAll(metric.getDimensions());
        }
        if (metric.getValue().containsKey("sum") && metric.getValue().containsKey("count")) {
            float val = metric.getValue().get("sum") / metric.getValue().get("count");
            metricMap.put("value", Float.toString(val));
        }
        return metricMap;
    }
}
