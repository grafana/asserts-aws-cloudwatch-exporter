/*
 *  Copyright © 2021.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.cloudwatch.alarms;

import ai.asserts.aws.ScrapeConfigProvider;
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
        String forwardUrl = scrapeConfigProvider.getScrapeConfig().getAlertForwardUrl();
        String tenant = scrapeConfigProvider.getScrapeConfig().getTenant();
        if (!CollectionUtils.isEmpty(labelsList)) {
            if (hasLength(forwardUrl)) {
                List<PrometheusAlert> alertList = labelsList.stream()
                        .map(this::createAlert)
                        .collect(Collectors.toList());
                PrometheusAlerts alerts = new PrometheusAlerts().withAlerts(alertList);
                if (hasLength(tenant) && !CollectionUtils.isEmpty(alerts.getAlerts())) {
                    HttpEntity<PrometheusAlerts> request = new HttpEntity<>(alerts,
                            scrapeConfigProvider.createAssertsAuthHeader().getHeaders());
                    try {
                        String url = String.format("%s?tenant=%s", forwardUrl, tenant);
                        log.info("Forwarding CloudWatch alarms as alerts to - {}", url);
                        ResponseEntity<String> responseEntity = restTemplate.exchange(url, POST, request, new ParameterizedTypeReference<String>() {
                        });
                        log.info("Got response code {} and response {}", responseEntity.getStatusCode(), responseEntity.getBody());
                    } catch (RestClientException e) {
                        log.error("Error sending alerts - {}", Arrays.toString(e.getStackTrace()));
                    }
                }
            } else {
                alarmMetricExporter.processMetric(labelsList);
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
