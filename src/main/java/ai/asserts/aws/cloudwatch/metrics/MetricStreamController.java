/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.cloudwatch.metrics;

import ai.asserts.aws.ApiAuthenticator;
import ai.asserts.aws.MetricNameUtil;
import ai.asserts.aws.ObjectMapperFactory;
import ai.asserts.aws.ScrapeConfigProvider;
import ai.asserts.aws.cloudwatch.alarms.FirehoseEventRequest;
import ai.asserts.aws.cloudwatch.alarms.RecordData;
import ai.asserts.aws.config.ScrapeConfig;
import ai.asserts.aws.exporter.BasicMetricCollector;
import ai.asserts.aws.model.CWNamespace;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.google.common.annotations.VisibleForTesting;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;

import static org.springframework.http.MediaType.APPLICATION_JSON_VALUE;

@AllArgsConstructor
@RestController
@Slf4j
@Component
@SuppressWarnings("unused")
public class MetricStreamController {
    public static final String METRICS = "/receive-cloudwatch-metrics";
    public static final String METRICS_SECURE = "/receive-cloudwatch-metrics-secure";
    private final ObjectMapperFactory objectMapperFactory;
    private final BasicMetricCollector metricCollector;
    private final MetricNameUtil metricNameUtil;
    private final ApiAuthenticator apiAuthenticator;

    private final ScrapeConfigProvider scrapeConfigProvider;

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

    @PostMapping(
            path = METRICS_SECURE,
            produces = APPLICATION_JSON_VALUE,
            consumes = APPLICATION_JSON_VALUE)
    public ResponseEntity<MetricResponse> receiveMetricsPostSecure(
            @RequestHeader("X-Amz-Firehose-Access-Key") String apiToken,
            @RequestBody FirehoseEventRequest metricRequest) {
        apiAuthenticator.authenticate(Optional.of(apiToken));
        processRequest(metricRequest);
        return ResponseEntity.ok(MetricResponse.builder()
                .status("Success")
                .build());
    }

    @PutMapping(
            path = METRICS_SECURE,
            produces = APPLICATION_JSON_VALUE,
            consumes = APPLICATION_JSON_VALUE)
    public ResponseEntity<MetricResponse> receiveMetricsPutSecure(
            @RequestHeader("X-Amz-Firehose-Access-Key") String apiToken,
            @RequestBody FirehoseEventRequest metricRequest) {
        apiAuthenticator.authenticate(Optional.of(apiToken));
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
            log.error("Error in processing {}-{}", ex, ex.getStackTrace());
        }
    }

    private void accept(RecordData data) {
        String decodedData = new String(Base64.getDecoder().decode(data.getData()));
        decodedData = decodedData.replace("}\n{", "},{");
        decodedData = "{\"metrics\":[" + decodedData + "]}";
        try {
            CloudWatchMetrics metrics =
                    objectMapperFactory.getObjectMapper().readValue(decodedData, CloudWatchMetrics.class);
            metrics.getMetrics().forEach(m -> {
                publishMetric(m);
                log.debug("Metric Name{} - Namespace {}", m.getMetric_name(), m.getNamespace());
            });
        } catch (JsonProcessingException jsp) {
            log.error("Error processing JSON {}-{}", decodedData, jsp.getMessage());
        }
    }

    private void publishMetric(CloudWatchMetric metric) {
        SortedMap<String, String> metricMap = new TreeMap<>();
        String metricNamespace = metric.getNamespace();
        metricMap.put("namespace", metricNamespace);
        metricMap.put("region", metric.getRegion());
        metricMap.put("account_id", metric.getAccount_id());
        if (!CollectionUtils.isEmpty(metric.getDimensions())) {
            metric.getDimensions().forEach((k, v) -> metricMap.put(metricNameUtil.toSnakeCase(k), v));
        }
        Optional<CWNamespace> namespaceOpt =
                Arrays.stream(CWNamespace.values()).filter(f -> f.getNamespace().equals(metricNamespace))
                        .findFirst();
        namespaceOpt.ifPresent(namespace -> {
            ScrapeConfig scrapeConfig = scrapeConfigProvider.getScrapeConfig();
            Map<String, String> dimensions = new TreeMap<>();
            Map<String, String> entityLabels =
                    new TreeMap<>(scrapeConfig.getEntityLabels(namespace.getNormalizedNamespace(),
                            metric.getDimensions()));

            // Exported metrics will be mapped through vendor rules. So no need for entity type
            // unlike the aws_cloudwatch_alarm
            entityLabels.remove("asserts_entity_type");
            metricMap.putAll(entityLabels);

            String prefix = namespace.getMetricPrefix();
            String metricName = prefix + "_" + metric.getMetric_name();
            recordHistogram(metricMap, metric.getTimestamp(), metricName);
            metric.getValue().forEach((key, value) -> {
                String gaugeMetricName = metricNameUtil.toSnakeCase(metricName + "_" + mapStat(key));
                metricCollector.recordGaugeValue(gaugeMetricName, metricMap, Double.valueOf(value));
            });
        });
    }

    private void recordHistogram(Map<String, String> labels, Long timestamp, String metric_name) {
        SortedMap<String, String> histogramLabels = new TreeMap<>();
        histogramLabels.put("namespace", labels.get("namespace"));
        histogramLabels.put("region", labels.get("region"));
        histogramLabels.put("metric_name", metric_name);
        long diff = (now().toEpochMilli() - timestamp) / 1000;
        this.metricCollector.recordHistogram(MetricNameUtil.EXPORTER_DELAY_SECONDS, histogramLabels, diff);
    }

    @VisibleForTesting
    Instant now() {
        return Instant.now();
    }

    String mapStat(String stat) {
        if ("count".equals(stat)) {
            return "samples";
        }
        return stat;
    }
}
