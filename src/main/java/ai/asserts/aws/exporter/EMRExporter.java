/*
 *  Copyright © 2022.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.exporter;

import ai.asserts.aws.AWSClientProvider;
import ai.asserts.aws.RateLimiter;
import ai.asserts.aws.TenantUtil;
import ai.asserts.aws.account.AccountProvider;
import com.google.common.collect.ImmutableSortedMap;
import io.prometheus.client.Collector;
import io.prometheus.client.CollectorRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.emr.EmrClient;
import software.amazon.awssdk.services.emr.model.ListClustersResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

import static ai.asserts.aws.MetricNameUtil.SCRAPE_ACCOUNT_ID_LABEL;
import static ai.asserts.aws.MetricNameUtil.SCRAPE_OPERATION_LABEL;
import static ai.asserts.aws.MetricNameUtil.SCRAPE_REGION_LABEL;

@Component
@Slf4j
public class EMRExporter extends Collector implements InitializingBean {
    public final CollectorRegistry collectorRegistry;
    private final AccountProvider accountProvider;
    private final AWSClientProvider awsClientProvider;
    private final RateLimiter rateLimiter;
    private final MetricSampleBuilder sampleBuilder;
    private final TenantUtil tenantUtil;
    private volatile List<MetricFamilySamples> metricFamilySamples = new ArrayList<>();

    public EMRExporter(
            AccountProvider accountProvider, AWSClientProvider awsClientProvider, CollectorRegistry collectorRegistry,
            RateLimiter rateLimiter, MetricSampleBuilder sampleBuilder, TenantUtil tenantUtil) {
        this.accountProvider = accountProvider;
        this.awsClientProvider = awsClientProvider;
        this.collectorRegistry = collectorRegistry;
        this.rateLimiter = rateLimiter;
        this.sampleBuilder = sampleBuilder;
        this.tenantUtil = tenantUtil;
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
        log.info("Exporting EMR Clusters");
        List<MetricFamilySamples> newFamily = new ArrayList<>();
        List<MetricFamilySamples.Sample> samples = new ArrayList<>();
        List<Future<?>> futures = new ArrayList<>();
        accountProvider.getAccounts().forEach(account -> account.getRegions().forEach(region ->
                futures.add(tenantUtil.executeTenantTask(account.getTenant(), () -> {
                    try {
                        EmrClient client = awsClientProvider.getEmrClient(region, account);
                        String api = "EmrClient/listClusters";
                        ListClustersResponse resp = rateLimiter.doWithRateLimit(
                                api, ImmutableSortedMap.of(
                                        SCRAPE_ACCOUNT_ID_LABEL, account.getAccountId(),
                                        SCRAPE_REGION_LABEL, region,
                                        SCRAPE_OPERATION_LABEL, api
                                ), client::listClusters);
                        if (resp.hasClusters()) {
                            samples.addAll(resp.clusters().stream()
                                    .filter(cluster -> cluster.status().state().name().equals("RUNNING"))
                                    .map(cluster -> {
                                        Map<String, String> labels = new TreeMap<>();
                                        labels.put(SCRAPE_ACCOUNT_ID_LABEL, account.getAccountId());
                                        labels.put(SCRAPE_REGION_LABEL, region);
                                        labels.put("namespace", "AWS/ElasticMapReduce");
                                        labels.put("aws_resource_type", "AWS::ElasticMapReduce::Cluster");
                                        labels.put("job", cluster.id());
                                        labels.put("name", cluster.name());
                                        labels.put("job_flow_id", cluster.id());
                                        return sampleBuilder.buildSingleSample("aws_resource", labels, 1.0D);
                                    })
                                    .filter(Optional::isPresent)
                                    .map(Optional::get)
                                    .collect(Collectors.toList()));
                        }
                    } catch (Exception e) {
                        log.error("Error:" + account, e);
                    }
                }))));
        tenantUtil.awaitAll(futures);
        sampleBuilder.buildFamily(samples).ifPresent(newFamily::add);
        metricFamilySamples = newFamily;
    }
}
