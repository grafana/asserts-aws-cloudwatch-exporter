/*
 *  Copyright © 2022.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.exporter;

import ai.asserts.aws.AWSClientProvider;
import ai.asserts.aws.AccountProvider;
import ai.asserts.aws.RateLimiter;
import com.google.common.collect.ImmutableSortedMap;
import io.prometheus.client.Collector;
import io.prometheus.client.CollectorRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.rds.RdsClient;
import software.amazon.awssdk.services.rds.model.DescribeDbClustersRequest;
import software.amazon.awssdk.services.rds.model.DescribeDbClustersResponse;
import software.amazon.awssdk.services.rds.model.DescribeDbInstancesRequest;
import software.amazon.awssdk.services.rds.model.DescribeDbInstancesResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
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
    private final RateLimiter rateLimiter;
    private final MetricSampleBuilder sampleBuilder;
    private volatile List<MetricFamilySamples> metricFamilySamples = new ArrayList<>();

    public RDSExporter(
            AccountProvider accountProvider, AWSClientProvider awsClientProvider, CollectorRegistry collectorRegistry,
            RateLimiter rateLimiter, MetricSampleBuilder sampleBuilder) {
        this.accountProvider = accountProvider;
        this.awsClientProvider = awsClientProvider;
        this.collectorRegistry = collectorRegistry;
        this.rateLimiter = rateLimiter;
        this.sampleBuilder = sampleBuilder;
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
        List<MetricFamilySamples.Sample> samples = new ArrayList<>();
        accountProvider.getAccounts().forEach(account -> account.getRegions().forEach(region -> {
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
                                    return sampleBuilder.buildSingleSample("aws_resource", labels, 1.0D);
                                })
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
                                    return sampleBuilder.buildSingleSample("aws_resource", labels, 1.0D);
                                })
                                .collect(Collectors.toList()));
                    }
                    nextToken.set(resp.marker());
                } while (nextToken.get() != null);
            } catch (Exception e) {
                log.error("Error" + account, e);
            }
        }));
        newFamily.add(sampleBuilder.buildFamily(samples));
        metricFamilySamples = newFamily;
    }
}
