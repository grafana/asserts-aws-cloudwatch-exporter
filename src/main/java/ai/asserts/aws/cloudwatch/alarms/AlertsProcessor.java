/*
 *  Copyright © 2021.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.cloudwatch.alarms;

import ai.asserts.aws.ScrapeConfigProvider;
import ai.asserts.aws.config.ScrapeConfig;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import static org.springframework.http.HttpMethod.POST;
import static org.springframework.util.StringUtils.hasLength;

@Component
@Slf4j
@AllArgsConstructor
public class AlertsProcessor {
    private final ScrapeConfigProvider scrapeConfigProvider;
    private final RestTemplate restTemplate;

    private final AlarmMetricExporter alarmMetricExporter;

    public void sendAlerts(List<Map<String, String>> labelsList) {
        ScrapeConfig scrapeConfig = scrapeConfigProvider.getScrapeConfig();
        if (!CollectionUtils.isEmpty(labelsList)) {
            if (scrapeConfig.isCwAlarmAsMetric()) {
                alarmMetricExporter.processMetric(labelsList);
            } else if (hasLength(scrapeConfig.getTenant()) && !CollectionUtils.isEmpty(labelsList)) {
                // Add scope labels
                List<PrometheusAlert> alertList = labelsList.stream()
                        .map(inputLabels -> scrapeConfig.additionalLabels("asserts:alerts", inputLabels))
                        .map(this::createAlert)
                        .collect(Collectors.toList());
                PrometheusAlerts alerts = new PrometheusAlerts().withAlerts(alertList);
                HttpEntity<PrometheusAlerts> request = new HttpEntity<>(alerts,
                        scrapeConfigProvider.createAssertsAuthHeader().getHeaders());
                try {
                    String url = String.format("%s?tenant=%s", scrapeConfigProvider.getAlertForwardUrl(), scrapeConfig.getTenant());
                    log.info("Forwarding CloudWatch alarms as alerts to - {}", url);
                    ResponseEntity<String> responseEntity = restTemplate.exchange(url, POST, request,
                            new ParameterizedTypeReference<String>() {
                            });
                    log.info("Got response code {} and response {}", responseEntity.getStatusCode(), responseEntity.getBody());
                } catch (RestClientException e) {
                    log.error("Error sending alerts - {}", Arrays.toString(e.getStackTrace()));
                }
            }
        }
    }

    private PrometheusAlert createAlert(Map<String, String> labels) {
        String timestamp = labels.get("timestamp");
        String state = labels.get("state");
        labels.remove("state");
        labels.remove("timestamp");
        labels.put("asserts_severity", "critical");
        labels.put("asserts_alert_category", "error");
        labels.put("asserts_source", "cloudwatch.alarms");
        labels.put("alertgroup", "cloudwatch");
        if ("ALARM".equals(state)) {
            return new PrometheusAlert()
                    .withLabels(new TreeMap<>(labels))
                    .withStatus(PrometheusAlertStatus.firing)
                    .withStartsAt(ZonedDateTime.parse(timestamp));
        }
        return new PrometheusAlert()
                .withLabels(new TreeMap<>(labels))
                .withStatus(PrometheusAlertStatus.resolved)
                .withStartsAt(ZonedDateTime.parse(timestamp));
    }
}
