/*
 *  Copyright © 2021.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.cloudwatch.alarms;

import ai.asserts.aws.AssertsServerUtil;
import ai.asserts.aws.ScrapeConfigProvider;
import ai.asserts.aws.config.ScrapeConfig;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.util.CollectionUtils;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import static ai.asserts.aws.MetricNameUtil.TENANT;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.util.StringUtils.hasLength;

@Slf4j
@AllArgsConstructor
public class AlertsProcessor {
    private final ScrapeConfigProvider scrapeConfigProvider;
    private final RestTemplate restTemplate;
    private final AlarmMetricExporter alarmMetricExporter;
    private final AssertsServerUtil assertsServerUtil;

    public void sendAlerts(List<Map<String, String>> labelsList) {
        if (labelsList.isEmpty()) {
            return;
        }
        String tenant = labelsList.get(0).get(TENANT);
        ScrapeConfig scrapeConfig = scrapeConfigProvider.getScrapeConfig(tenant);
        if (!CollectionUtils.isEmpty(labelsList)) {
            if (scrapeConfig.isCwAlarmAsMetric()) {
                log.info("Exporting alarms as metric");
                alarmMetricExporter.processMetric(labelsList);
            } else if (hasLength(scrapeConfig.getTenant()) && !CollectionUtils.isEmpty(labelsList)) {
                log.info("Exporting alarms as prometheus alerts");
                // Add scope labels
                List<PrometheusAlert> alertList = labelsList.stream()
                        .map(this::createAlert)
                        .collect(Collectors.toList());
                PrometheusAlerts alerts = new PrometheusAlerts().withAlerts(alertList);
                HttpEntity<PrometheusAlerts> request = new HttpEntity<>(alerts,
                        assertsServerUtil.createAssertsAuthHeader().getHeaders());
                try {
                    String url = String.format("%s?tenant=%s", assertsServerUtil.getAlertForwardUrl(),
                            scrapeConfig.getTenant());
                    log.info("Forwarding {} CloudWatch alarms as alerts to - {}", alertList.size(), url);
                    ResponseEntity<String> responseEntity = restTemplate.exchange(url, POST, request,
                            new ParameterizedTypeReference<String>() {
                            });
                    log.info("Got response code {} and response {}", responseEntity.getStatusCode(),
                            responseEntity.getBody());
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
