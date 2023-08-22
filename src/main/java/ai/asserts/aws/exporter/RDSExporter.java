/*
 *  Copyright © 2022.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.exporter;

import ai.asserts.aws.AWSClientProvider;
import ai.asserts.aws.CollectionBuilderTask;
import ai.asserts.aws.AWSApiCallRateLimiter;
import ai.asserts.aws.ScrapeConfigProvider;
import ai.asserts.aws.TagUtil;
import ai.asserts.aws.TaskExecutorUtil;
import ai.asserts.aws.account.AWSAccount;
import ai.asserts.aws.account.AccountProvider;
import ai.asserts.aws.config.ScrapeConfig;
import ai.asserts.aws.resource.Resource;
import ai.asserts.aws.resource.ResourceTagHelper;
import com.google.common.collect.ImmutableSortedMap;
import io.prometheus.client.Collector;
import io.prometheus.client.Collector.MetricFamilySamples.Sample;
import io.prometheus.client.CollectorRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.rds.RdsClient;
import software.amazon.awssdk.services.rds.model.DBCluster;
import software.amazon.awssdk.services.rds.model.DBInstance;
import software.amazon.awssdk.services.rds.model.DescribeDbClustersRequest;
import software.amazon.awssdk.services.rds.model.DescribeDbClustersResponse;
import software.amazon.awssdk.services.rds.model.DescribeDbInstancesRequest;
import software.amazon.awssdk.services.rds.model.DescribeDbInstancesResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static ai.asserts.aws.MetricNameUtil.SCRAPE_ACCOUNT_ID_LABEL;
import static ai.asserts.aws.MetricNameUtil.SCRAPE_OPERATION_LABEL;
import static ai.asserts.aws.MetricNameUtil.SCRAPE_REGION_LABEL;

@Component
@Slf4j
public class RDSExporter extends Collector implements InitializingBean {
    public final CollectorRegistry collectorRegistry;
    private final AccountProvider accountProvider;
    private final AWSClientProvider awsClientProvider;
    private final AWSApiCallRateLimiter rateLimiter;
    private final MetricSampleBuilder sampleBuilder;
    private final ResourceTagHelper resourceTagHelper;
    private final TagUtil tagUtil;
    private final TaskExecutorUtil taskExecutorUtil;
    private final ScrapeConfigProvider scrapeConfigProvider;
    private volatile List<MetricFamilySamples> metricFamilySamples = new ArrayList<>();

    public RDSExporter(
            AccountProvider accountProvider, AWSClientProvider awsClientProvider, CollectorRegistry collectorRegistry,
            AWSApiCallRateLimiter rateLimiter, MetricSampleBuilder sampleBuilder, ResourceTagHelper resourceTagHelper,
            TagUtil tagUtil, TaskExecutorUtil taskExecutorUtil, ScrapeConfigProvider scrapeConfigProvider) {
        this.accountProvider = accountProvider;
        this.awsClientProvider = awsClientProvider;
        this.collectorRegistry = collectorRegistry;
        this.rateLimiter = rateLimiter;
        this.sampleBuilder = sampleBuilder;
        this.resourceTagHelper = resourceTagHelper;
        this.tagUtil = tagUtil;
        this.taskExecutorUtil = taskExecutorUtil;
        this.scrapeConfigProvider = scrapeConfigProvider;
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
        log.info("Exporting RDS DBClusters / DBInstances");
        List<MetricFamilySamples> newFamily = new ArrayList<>();
        List<Sample> allSamples = new ArrayList<>();
        List<Future<List<Sample>>> futures = new ArrayList<>();
        accountProvider.getAccounts().forEach(account -> account.getRegions().forEach(region ->
                futures.add(taskExecutorUtil.executeAccountTask(account, new CollectionBuilderTask<Sample>() {
                    @Override
                    public List<Sample> call() {
                        return buildSamples(region, account);
                    }
                }))));
        taskExecutorUtil.awaitAll(futures, allSamples::addAll);
        sampleBuilder.buildFamily(allSamples).ifPresent(newFamily::add);
        metricFamilySamples = newFamily;
    }

    private List<Sample> buildSamples(String region, AWSAccount account) {
        ScrapeConfig scrapeConfig = scrapeConfigProvider.getScrapeConfig(account.getTenant());
        List<Sample> samples = new ArrayList<>();
        try {
            RdsClient client = awsClientProvider.getRDSClient(region, account);
            AtomicReference<String> nextToken = new AtomicReference<>();
            do {
                String api = "RdsClient/describeDBClusters";
                DescribeDbClustersResponse resp = rateLimiter.doWithRateLimit(
                        api, ImmutableSortedMap.of(
                                SCRAPE_ACCOUNT_ID_LABEL, account.getAccountId(),
                                SCRAPE_REGION_LABEL, region,
                                SCRAPE_OPERATION_LABEL, api
                        ), () -> client.describeDBClusters(DescribeDbClustersRequest.builder()
                                .marker(nextToken.get()).build()));
                if (resp.hasDbClusters()) {
                    Map<String, Resource> byName =
                            resourceTagHelper.getResourcesWithTag(account, region, "rds:cluster",
                                    resp.dbClusters().stream().map(DBCluster::dbClusterIdentifier)
                                            .collect(Collectors.toList()));
                    samples.addAll(resp.dbClusters().stream()
                            .map(cluster -> {
                                Map<String, String> labels = new TreeMap<>();
                                labels.put(SCRAPE_ACCOUNT_ID_LABEL, account.getAccountId());
                                labels.put(SCRAPE_REGION_LABEL, region);
                                labels.put("aws_resource_type", "AWS::RDS::DBCluster");
                                labels.put("job", cluster.dbClusterIdentifier());
                                labels.put("name", cluster.dbClusterIdentifier());
                                labels.put("id", cluster.dbClusterIdentifier());
                                labels.put("namespace", "AWS/RDS");
                                if (byName.containsKey(cluster.dbClusterIdentifier())) {
                                    labels.putAll(
                                            tagUtil.tagLabels(
                                                    scrapeConfig,
                                                    byName.get(cluster.dbClusterIdentifier()).getTags()));
                                }
                                return sampleBuilder.buildSingleSample("aws_resource", labels, 1.0D);
                            })
                            .filter(Optional::isPresent)
                            .map(Optional::get)
                            .collect(Collectors.toList()));
                }
                nextToken.set(resp.marker());
            } while (nextToken.get() != null);

            do {
                String api = "RdsClient/describeDBInstances";
                DescribeDbInstancesResponse resp = rateLimiter.doWithRateLimit(
                        api, ImmutableSortedMap.of(
                                SCRAPE_ACCOUNT_ID_LABEL, account.getAccountId(),
                                SCRAPE_REGION_LABEL, region,
                                SCRAPE_OPERATION_LABEL, api
                        ), () -> client.describeDBInstances(DescribeDbInstancesRequest.builder()
                                .marker(nextToken.get()).build()));
                if (resp.hasDbInstances()) {
                    Map<String, Resource> byName =
                            resourceTagHelper.getResourcesWithTag(account, region, "rds:db",
                                    resp.dbInstances().stream().map(DBInstance::dbInstanceIdentifier)
                                            .collect(Collectors.toList()));
                    samples.addAll(resp.dbInstances().stream()
                            .map(dbInstance -> {
                                Map<String, String> labels = new TreeMap<>();
                                labels.put(SCRAPE_ACCOUNT_ID_LABEL, account.getAccountId());
                                labels.put(SCRAPE_REGION_LABEL, region);
                                labels.put("aws_resource_type", "AWS::RDS::DBInstance");
                                labels.put("job", dbInstance.dbInstanceIdentifier());
                                labels.put("name", dbInstance.dbInstanceIdentifier());
                                labels.put("id", dbInstance.dbInstanceIdentifier());
                                labels.put("namespace", "AWS/RDS");
                                if (byName.containsKey(dbInstance.dbInstanceIdentifier())) {
                                    labels.putAll(tagUtil.tagLabels(
                                            scrapeConfig,
                                            byName.get(dbInstance.dbInstanceIdentifier()).getTags()));
                                }
                                return sampleBuilder.buildSingleSample("aws_resource", labels, 1.0D);
                            })
                            .filter(Optional::isPresent)
                            .map(Optional::get)
                            .collect(Collectors.toList()));
                }
                nextToken.set(resp.marker());
            } while (nextToken.get() != null);
        } catch (Exception e) {
            log.error("Failed to export RDS Metrics for " + account, e);
        }
        return samples;
    }
}
