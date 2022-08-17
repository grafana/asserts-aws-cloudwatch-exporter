/*
 *  Copyright © 2022.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.exporter;

import ai.asserts.aws.AWSClientProvider;
import ai.asserts.aws.AccountProvider;
import ai.asserts.aws.RateLimiter;
import ai.asserts.aws.TagUtil;
import ai.asserts.aws.resource.Resource;
import ai.asserts.aws.resource.ResourceTagHelper;
import com.google.common.collect.ImmutableSortedMap;
import io.prometheus.client.Collector;
import io.prometheus.client.CollectorRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.ListTablesResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import static ai.asserts.aws.MetricNameUtil.SCRAPE_ACCOUNT_ID_LABEL;
import static ai.asserts.aws.MetricNameUtil.SCRAPE_OPERATION_LABEL;
import static ai.asserts.aws.MetricNameUtil.SCRAPE_REGION_LABEL;

@Component
@Slf4j
public class DynamoDBExporter extends Collector implements InitializingBean {
    public final CollectorRegistry collectorRegistry;
    private final AccountProvider accountProvider;
    private final AWSClientProvider awsClientProvider;
    private final RateLimiter rateLimiter;
    private final MetricSampleBuilder sampleBuilder;

    private final ResourceTagHelper resourceTagHelper;

    private final TagUtil tagUtil;
    private volatile List<MetricFamilySamples> metricFamilySamples = new ArrayList<>();

    public DynamoDBExporter(
            AccountProvider accountProvider, AWSClientProvider awsClientProvider, CollectorRegistry collectorRegistry,
            RateLimiter rateLimiter, MetricSampleBuilder sampleBuilder, ResourceTagHelper resourceTagHelper,
            TagUtil tagUtil) {
        this.accountProvider = accountProvider;
        this.awsClientProvider = awsClientProvider;
        this.collectorRegistry = collectorRegistry;
        this.rateLimiter = rateLimiter;
        this.sampleBuilder = sampleBuilder;
        this.resourceTagHelper = resourceTagHelper;
        this.tagUtil = tagUtil;
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        register(collectorRegistry);
    }

    @Override
    public List<MetricFamilySamples> collect() {
        return metricFamilySamples;
    }

    public void update() {
        log.info("Exporting DynamoDB Table Resources");
        List<MetricFamilySamples> newFamily = new ArrayList<>();
        List<MetricFamilySamples.Sample> samples = new ArrayList<>();
        accountProvider.getAccounts().forEach(account -> account.getRegions().forEach(region -> {
            try {
                DynamoDbClient client = awsClientProvider.getDynamoDBClient(region, account);
                String api = "DynamoDbClient/listTables";
                ListTablesResponse resp = rateLimiter.doWithRateLimit(
                        api, ImmutableSortedMap.of(
                                SCRAPE_ACCOUNT_ID_LABEL, account.getAccountId(),
                                SCRAPE_REGION_LABEL, region,
                                SCRAPE_OPERATION_LABEL, api
                        ), client::listTables);
                if (resp.hasTableNames()) {
                    Map<String, Resource> resourcesWithTag = resourceTagHelper.getResourcesWithTag(account, region,
                            "dynamodb:table", resp.tableNames());

                    samples.addAll(resp.tableNames().stream()
                            .map(tableName -> {
                                Map<String, String> labels = new TreeMap<>();
                                labels.put(SCRAPE_ACCOUNT_ID_LABEL, account.getAccountId());
                                labels.put(SCRAPE_REGION_LABEL, region);
                                labels.put("aws_resource_type", "AWS::DynamoDB::Table");
                                labels.put("job", tableName);
                                labels.put("name", tableName);
                                labels.put("id", tableName);
                                labels.put("namespace", "AWS/DynamoDB");
                                if (resourcesWithTag.containsKey(tableName)) {
                                    labels.putAll(tagUtil.tagLabels(resourcesWithTag.get(tableName).getTags()));
                                }

                                return sampleBuilder.buildSingleSample("aws_resource", labels, 1.0D);
                            })
                            .collect(Collectors.toList()));
                }


            } catch (Exception e) {
                log.error("Error : " + account, e);
            }
        }));
        newFamily.add(sampleBuilder.buildFamily(samples));
        metricFamilySamples = newFamily;
    }
}
