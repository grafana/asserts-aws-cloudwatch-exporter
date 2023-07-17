/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.cloudwatch.metrics;

import ai.asserts.aws.ApiAuthenticator;
import ai.asserts.aws.MetricNameUtil;
import ai.asserts.aws.ObjectMapperFactory;
import ai.asserts.aws.ScrapeConfigProvider;
import ai.asserts.aws.account.AccountTenantMapper;
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

import static ai.asserts.aws.MetricNameUtil.TENANT;
import static org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR;
import static org.springframework.http.HttpStatus.UNAUTHORIZED;
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
    private final AccountTenantMapper accountTenantMapper;

    @PostMapping(
            path = METRICS,
            produces = APPLICATION_JSON_VALUE,
            consumes = APPLICATION_JSON_VALUE)
    public ResponseEntity<MetricResponse> receiveMetricsPost(
            @RequestBody FirehoseEventRequest metricRequest) {
        try {
            processRequest(metricRequest);
            return ResponseEntity.ok(MetricResponse.builder()
                    .requestId(metricRequest.getRequestId())
                    .timestamp(System.currentTimeMillis())
                    .build());
        } catch (Throwable e) {
            return ResponseEntity.status(INTERNAL_SERVER_ERROR)
                    .body(MetricResponse.builder()
                            .requestId(metricRequest.getRequestId())
                            .timestamp(System.currentTimeMillis())
                            .errorMessage(e.getMessage())
                            .build());
        }
    }

    @PutMapping(
            path = METRICS,
            produces = APPLICATION_JSON_VALUE,
            consumes = APPLICATION_JSON_VALUE)
    public ResponseEntity<MetricResponse> receiveMetricsPut(
            @RequestBody FirehoseEventRequest metricRequest) {
        try {
            processRequest(metricRequest);
            return ResponseEntity.ok(MetricResponse.builder()
                    .requestId(metricRequest.getRequestId())
                    .timestamp(System.currentTimeMillis())
                    .build());
        } catch (Throwable e) {
            return ResponseEntity.status(INTERNAL_SERVER_ERROR)
                    .body(MetricResponse.builder()
                            .requestId(metricRequest.getRequestId())
                            .timestamp(System.currentTimeMillis())
                            .errorMessage(e.getMessage())
                            .build());
        }
    }

    @PostMapping(
            path = METRICS_SECURE,
            produces = APPLICATION_JSON_VALUE,
            consumes = APPLICATION_JSON_VALUE)
    public ResponseEntity<MetricResponse> receiveMetricsPostSecure(
            @RequestHeader("X-Amz-Firehose-Access-Key") String apiToken,
            @RequestBody FirehoseEventRequest metricRequest) {
        try {
            apiAuthenticator.authenticate(Optional.of(apiToken));
        } catch (RuntimeException e) {
            return ResponseEntity.status(UNAUTHORIZED)
                    .body(MetricResponse.builder()
                            .requestId(metricRequest.getRequestId())
                            .timestamp(System.currentTimeMillis())
                            .errorMessage("Authentication Failure")
                            .build());
        }
        try {
            processRequest(metricRequest);
            return ResponseEntity.ok(MetricResponse.builder()
                    .requestId(metricRequest.getRequestId())
                    .timestamp(System.currentTimeMillis())
                    .build());
        } catch (Throwable e) {
            return ResponseEntity.status(INTERNAL_SERVER_ERROR)
                    .body(MetricResponse.builder()
                            .requestId(metricRequest.getRequestId())
                            .timestamp(System.currentTimeMillis())
                            .errorMessage(e.getMessage())
                            .build());
        }
    }

    @PutMapping(
            path = METRICS_SECURE,
            produces = APPLICATION_JSON_VALUE,
            consumes = APPLICATION_JSON_VALUE)
    public ResponseEntity<MetricResponse> receiveMetricsPutSecure(
            @RequestHeader("X-Amz-Firehose-Access-Key") String apiToken,
            @RequestBody FirehoseEventRequest metricRequest) {
        try {
            apiAuthenticator.authenticate(Optional.of(apiToken));
        } catch (RuntimeException e) {
            return ResponseEntity.status(UNAUTHORIZED)
                    .body(MetricResponse.builder()
                            .requestId(metricRequest.getRequestId())
                            .timestamp(System.currentTimeMillis())
                            .errorMessage("Authentication Failure")
                            .build());
        }
        try {
            processRequest(metricRequest);
            return ResponseEntity.ok(MetricResponse.builder()
                    .requestId(metricRequest.getRequestId())
                    .timestamp(System.currentTimeMillis())
                    .build());
        } catch (Throwable e) {
            return ResponseEntity.status(INTERNAL_SERVER_ERROR)
                    .body(MetricResponse.builder()
                            .requestId(metricRequest.getRequestId())
                            .timestamp(System.currentTimeMillis())
                            .errorMessage(e.getMessage())
                            .build());
        }
    }

    @VisibleForTesting
    void processRequest(FirehoseEventRequest firehoseEventRequest) {
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
            metrics.getMetrics().stream()
                    .filter(this::shouldCaptureMetric)
                    .forEach(m -> {
                        publishMetric(m);
                        log.debug("Metric Name{} - Namespace {}", m.getMetric_name(), m.getNamespace());
                    });
        } catch (JsonProcessingException jsp) {
            log.error("Error processing JSON {}-{}", decodedData, jsp.getMessage());
        }
    }

    boolean shouldCaptureMetric(CloudWatchMetric metric) {
        Optional<CWNamespace> ns = scrapeConfigProvider.getStandardNamespace(metric.getNamespace());
        if (ns.isPresent()) {
            String tenant = accountTenantMapper.getTenantName(metric.getAccount_id());
            ScrapeConfig scrapeConfig = scrapeConfigProvider.getScrapeConfig(tenant);
            String nsPrefix = ns.get().getMetricPrefix();
            return metric.getValue().keySet().stream()
                    .map(stat -> metricNameUtil.toSnakeCase(nsPrefix + "_" + metric.getMetric_name() + "_" + stat))
                    .anyMatch(metricName -> scrapeConfig.getMetricsToCapture().containsKey(metricName));
        }
        return false;
    }


    private void publishMetric(CloudWatchMetric metric) {
        String tenant = accountTenantMapper.getTenantName(metric.getAccount_id());
        ScrapeConfig scrapeConfig = scrapeConfigProvider.getScrapeConfig(tenant);
        SortedMap<String, String> metricMap = new TreeMap<>();
        String metricNamespace = metric.getNamespace();

        if (tenant != null) {
            metricMap.put(TENANT, tenant);
        }
        metricMap.put("account_id", metric.getAccount_id());
        metricMap.put("region", metric.getRegion());
        metricMap.put("namespace", metricNamespace);

        if (!CollectionUtils.isEmpty(metric.getDimensions())) {
            metric.getDimensions().forEach((k, v) -> metricMap.put("d_" + metricNameUtil.toSnakeCase(k), v));
        }
        Optional<CWNamespace> namespaceOpt =
                Arrays.stream(CWNamespace.values()).filter(f -> f.getNamespace().equals(metricNamespace))
                        .findFirst();
        namespaceOpt.ifPresent(namespace -> {
            String prefix = namespace.getMetricPrefix();
            String metricName = prefix + "_" + metric.getMetric_name();
            metric.getValue().forEach((key, value) -> {
                String gaugeMetricName = metricNameUtil.toSnakeCase(metricName + "_" + key);
                if (scrapeConfig.getMetricsToCapture().containsKey(gaugeMetricName)) {
                    metricCollector.recordGaugeValue(gaugeMetricName, metricMap, Double.valueOf(value));
                }
            });
        });
    }

    private void recordHistogram(String tenant, Map<String, String> labels, Long timestamp, String metric_name) {
        SortedMap<String, String> histogramLabels = new TreeMap<>();
        histogramLabels.put("namespace", labels.get("namespace"));
        histogramLabels.put("account_id", labels.get("account_id"));
        histogramLabels.put("region", labels.get("region"));
        histogramLabels.put("metric_name", metric_name);
        if (tenant != null) {
            histogramLabels.put(TENANT, tenant);
        }
        long diff = (now().toEpochMilli() - timestamp) / 1000;
        this.metricCollector.recordHistogram(MetricNameUtil.EXPORTER_DELAY_SECONDS, histogramLabels, diff);
    }

    @VisibleForTesting
    Instant now() {
        return Instant.now();
    }
}
