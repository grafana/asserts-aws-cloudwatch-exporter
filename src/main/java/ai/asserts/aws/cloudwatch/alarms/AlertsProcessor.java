/*
 *  Copyright © 2021.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.cloudwatch.alarms;

import ai.asserts.aws.cloudwatch.config.ScrapeConfigProvider;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
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

@Component
@Slf4j
@AllArgsConstructor
public class AlertsProcessor {
    private final ScrapeConfigProvider scrapeConfigProvider;
    private final RestTemplate restTemplate;

    public void sendAlerts(List<Map<String, String>> labelsList) {
        String forwardUrl = scrapeConfigProvider.getScrapeConfig().getAlertForwardUrl();
        String tenant = scrapeConfigProvider.getScrapeConfig().getTenant();
        if (forwardUrl != null && tenant != null) {
            List<PrometheusAlert> alertList = labelsList.stream().map(this::createAlert).collect(Collectors.toList());
            PrometheusAlerts alerts = new PrometheusAlerts().withAlerts(alertList);
            if (!CollectionUtils.isEmpty(alerts.getAlerts())) {
                HttpEntity<PrometheusAlerts> request = new HttpEntity<>(alerts);
                try {
                    String urlToSend = String.format("%s?tenant=%s", forwardUrl, tenant);
                    log.info("Sending alert to - {}", urlToSend);
                    restTemplate.postForObject(urlToSend, request, Object.class);
                } catch (RestClientException e) {
                    log.error("Error sending alerts - {}" , Arrays.toString(e.getStackTrace()));
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
