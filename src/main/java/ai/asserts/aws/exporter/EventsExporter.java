/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.exporter;

import ai.asserts.aws.AWSClientProvider;
import ai.asserts.aws.RateLimiter;
import ai.asserts.aws.cloudwatch.TimeWindowBuilder;
import ai.asserts.aws.cloudwatch.config.ScrapeConfig;
import ai.asserts.aws.cloudwatch.config.ScrapeConfigProvider;
import com.google.common.collect.ImmutableSortedMap;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.prometheus.client.Collector;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.cloudtrail.CloudTrailClient;
import software.amazon.awssdk.services.cloudtrail.model.Event;
import software.amazon.awssdk.services.cloudtrail.model.LookupAttribute;
import software.amazon.awssdk.services.cloudtrail.model.LookupAttributeKey;
import software.amazon.awssdk.services.cloudtrail.model.LookupEventsRequest;
import software.amazon.awssdk.services.cloudtrail.model.LookupEventsResponse;
import software.amazon.awssdk.services.config.model.ResourceType;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

import static ai.asserts.aws.MetricNameUtil.SCRAPE_OPERATION_LABEL;
import static ai.asserts.aws.MetricNameUtil.SCRAPE_REGION_LABEL;

@Component
@Slf4j
public class EventsExporter extends Collector implements MetricProvider {
    private final ScrapeConfigProvider scrapeConfigProvider;
    private final AWSClientProvider awsClientProvider;
    private final RateLimiter rateLimiter;
    private final MetricSampleBuilder sampleBuilder;
    private final TimeWindowBuilder timeWindowBuilder;
    private final String ALARMS_TYPE = "AWS::CloudWatch::Alarm";
    private volatile List<MetricFamilySamples> metrics = new ArrayList<>();
    private Map<String, MetricFamilySamples.Sample> alert_samples = new TreeMap<>();
    private List<String> deletedAlarms = new LinkedList<>();

    public EventsExporter(ScrapeConfigProvider scrapeConfigProvider,
                          AWSClientProvider awsClientProvider,
                          RateLimiter rateLimiter,
                          MetricSampleBuilder sampleBuilder,
                          TimeWindowBuilder timeWindowBuilder) {
        this.scrapeConfigProvider = scrapeConfigProvider;
        this.awsClientProvider = awsClientProvider;
        this.rateLimiter = rateLimiter;
        this.sampleBuilder = sampleBuilder;
        this.timeWindowBuilder = timeWindowBuilder;
    }

    @Override
    public void update() {
        ScrapeConfig scrapeConfig = scrapeConfigProvider.getScrapeConfig();
        Integer intervalSeconds = scrapeConfig.getScrapeInterval();
        Integer delaySeconds = scrapeConfig.getDelay();
        Set<String> discoverResourceTypes = scrapeConfig.getDiscoverResourceTypes();
        discoverResourceTypes.add(ALARMS_TYPE);
        List<MetricFamilySamples.Sample> samples = new ArrayList<>();
        if (scrapeConfig.isImportEvents()) {
            scrapeConfig.getRegions().forEach(region -> {
                try (CloudTrailClient cloudTrailClient = awsClientProvider.getCloudTrailClient(region)) {
                    discoverResourceTypes.forEach(resource -> {
                        log.info("Importing Events for region {}-{}", region, resource);
                        String nextToken = null;
                        Instant[] timePeriod = timeWindowBuilder.getTimePeriod(region, intervalSeconds);
                        do {
                            LookupEventsRequest request = LookupEventsRequest.builder()
                                    .startTime(timePeriod[0].minusSeconds(delaySeconds))
                                    .endTime(timePeriod[1].minusSeconds(delaySeconds))
                                    .maxResults(20)
                                    .nextToken(nextToken)
                                    .lookupAttributes(LookupAttribute.builder()
                                            .attributeKey(LookupAttributeKey.RESOURCE_TYPE)
                                            .attributeValue(resource)
                                            .build())
                                    .build();
                            LookupEventsResponse response = rateLimiter.doWithRateLimit("CloudTrailClient/lookupEvents",
                                    ImmutableSortedMap.of(
                                            SCRAPE_REGION_LABEL, region,
                                            SCRAPE_OPERATION_LABEL, "lookupEvents"
                                    ),
                                    () -> cloudTrailClient.lookupEvents(request));
                            if (response.hasEvents()) {
                                response.events().forEach(event -> {
                                    if (resource.equals(ALARMS_TYPE)) {
                                        if (isAlarmFiring(event)) {
                                            createErrorAlert(event, resource, region);
                                        } else {
                                            stopAlarm(event);
                                        }
                                    } else {
                                        samples.add(createAmendAlert(event, resource, region));
                                    }
                                });
                            }
                            nextToken = response.nextToken();
                        } while (nextToken != null);
                    });
                } catch (Exception e) {
                    log.error("Failed to extract events", e);
                }
            });
            List<MetricFamilySamples> latest = new ArrayList<>();
            if (samples.size() > 0) {
                log.info("Adding {} Events", samples.size());
                latest.add(sampleBuilder.buildFamily(samples));
            }
            if (alert_samples.size() > 0) {
                log.info("Adding {} Alerts", alert_samples.size());
                latest.add(sampleBuilder.buildFamily(new ArrayList<>(alert_samples.values())));
            }
            metrics = latest;
            removeAlarms();
        }
    }

    private MetricFamilySamples.Sample createAmendAlert(Event event, String resource, String region) {
        SortedMap<String, String> labels = new TreeMap<>();
        labels.put(SCRAPE_REGION_LABEL, region);
        String event_name = event.eventName();
        labels.put("alertname", event_name);
        labels.put("alertstate", "firing");
        labels.put("alertgroup", "aws_exporter");
        labels.put("asserts_alert_category", "amend");
        labels.put("asserts_severity", "info");
        labels.put("asserts_source", event.eventSource());
        event.resources().forEach(resource1 ->
        {
            if (resource.equals(resource1.resourceType())) {
                labels.put("service", resource1.resourceName());
                labels.put("job", resource1.resourceName());
                labels.put("asserts_entity_type", "Service");
                labels.put("namespace", getNamespace(resource));
            }
        });
        return sampleBuilder.buildSingleSample("ALERTS", labels, 1.0);
    }

    private void createErrorAlert(Event event, String resource, String region) {
        SortedMap<String, String> labels = new TreeMap<>();
        labels.put(SCRAPE_REGION_LABEL, region);
        String event_detail = event.cloudTrailEvent();
        JsonElement element = JsonParser.parseString(event_detail);
        String alarmName = null;
        String namespace = null;
        if (element.isJsonObject()) {
            JsonObject json_obj = (JsonObject) element;
            JsonElement requestParam = json_obj.get("requestParameters");
            alarmName = getJsonProperty(requestParam, "alarmName");
            namespace = getJsonProperty(requestParam, "namespace");

        }
        if (alarmName != null && namespace != null) {
            labels.put("alertname", alarmName);
            labels.put("alertstate", "firing");
            labels.put("alertgroup", "aws_exporter");
            labels.put("asserts_alert_category", "error");
            labels.put("asserts_severity", "warning");
            labels.put("asserts_source", event.eventSource());
            labels.put("namespace", namespace);
            event.resources().forEach(resource1 ->
            {
                if (resource.equals(resource1.resourceType())) {
                    labels.put("service", resource1.resourceName());
                    labels.put("job", resource1.resourceName());
                    labels.put("asserts_entity_type", "Service");
                }
            });
            MetricFamilySamples.Sample sample = sampleBuilder.buildSingleSample("ALERTS", labels, 1.0);
            alert_samples.put(labels.get("job"), sample);
        }
    }

    private String getJsonProperty(JsonElement requestParam, String name) {
        if (requestParam != null) {
            JsonObject json_requestParam = (JsonObject) requestParam;
            JsonElement alarmName_ele = json_requestParam.get(name);
            if (alarmName_ele != null && alarmName_ele.isJsonPrimitive()) {
                return alarmName_ele.getAsString();
            }
        }
        return null;
    }

    private boolean isAlarmFiring(Event event) {
        return "PutMetricAlarm".equals(event.eventName());
    }

    private String getNamespace(String resource) {
        ResourceType type = ResourceType.fromValue(resource);
        String namespace = "";
        switch (type) {
            case AWS_LAMBDA_FUNCTION:
                namespace = "AWS/Lambda";
                break;
            case AWS_SQS_QUEUE:
                namespace = "AWS/SQS";
                break;
            case AWS_EC2_INSTANCE:
                namespace = "AWS/EC2";
                break;
            case AWS_EC2_VOLUME:
                namespace = "AWS/EBS";
                break;
            case AWS_EFS_FILE_SYSTEM:
                namespace = "AWS/EFS";
                break;
            case AWS_EC2_NAT_GATEWAY:
                namespace = "AWS/NATGateway";
                break;
            case AWS_ELASTIC_LOAD_BALANCING_V2_LOAD_BALANCER:
                namespace = "AWS/ApplicationELB";
                break;
            case AWS_ELASTIC_LOAD_BALANCING_LOAD_BALANCER:
                namespace = "AWS/ELB";
                break;
            case AWS_RDS_DB_INSTANCE:
                namespace = "AWS/RDS";
                break;
            case AWS_S3_BUCKET:
                namespace = "AWS/S3";
                break;
            case AWS_DYNAMO_DB_TABLE:
                namespace = "AWS/DynamoDB";
                break;
            case AWS_API_GATEWAY_REST_API:
            case AWS_API_GATEWAY_V2_API:
                namespace = "AWS/ApiGateway";
                break;
            case AWS_SNS_TOPIC:
                namespace = "AWS/SNS";
                break;
            case AWS_KINESIS_STREAM:
            case AWS_KINESIS_STREAM_CONSUMER:
                namespace = "AWS/Kinesis";
                break;
            case AWS_ECS_SERVICE:
                namespace = "AWS/ECS";
                break;
        }
        return namespace;
    }

    private void stopAlarm(Event event) {
        if ("DeleteAlarms".equals(event.eventName())) {
            event.resources().forEach(resource -> {
                deletedAlarms.add(resource.resourceName());
            });
        }
    }

    private void removeAlarms() {
        if (deletedAlarms.size() > 0) {
            deletedAlarms.forEach(key -> {
                alert_samples.remove(key);
            });
        }
    }

    @Override
    public List<MetricFamilySamples> collect() {
        return metrics;
    }
}
