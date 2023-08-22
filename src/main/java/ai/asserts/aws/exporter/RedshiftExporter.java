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
import com.google.common.collect.ImmutableSortedMap;
import io.prometheus.client.Collector;
import io.prometheus.client.Collector.MetricFamilySamples.Sample;
import io.prometheus.client.CollectorRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.redshift.RedshiftClient;
import software.amazon.awssdk.services.redshift.model.DescribeClustersResponse;
import software.amazon.awssdk.services.resourcegroupstaggingapi.model.Tag;

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
public class RedshiftExporter extends Collector implements InitializingBean {
    public final CollectorRegistry collectorRegistry;
    private final AccountProvider accountProvider;
    private final AWSClientProvider awsClientProvider;
    private final AWSApiCallRateLimiter rateLimiter;
    private final MetricSampleBuilder sampleBuilder;
    private final TagUtil tagUtil;
    private final TaskExecutorUtil taskExecutorUtil;
    private final ScrapeConfigProvider scrapeConfigProvider;
    private volatile List<MetricFamilySamples> metricFamilySamples = new ArrayList<>();

    public RedshiftExporter(
            AccountProvider accountProvider, AWSClientProvider awsClientProvider, CollectorRegistry collectorRegistry,
            AWSApiCallRateLimiter rateLimiter, MetricSampleBuilder sampleBuilder, TagUtil tagUtil,
            TaskExecutorUtil taskExecutorUtil, ScrapeConfigProvider scrapeConfigProvider) {
        this.accountProvider = accountProvider;
        this.awsClientProvider = awsClientProvider;
        this.collectorRegistry = collectorRegistry;
        this.rateLimiter = rateLimiter;
        this.sampleBuilder = sampleBuilder;
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
        log.info("Exporting Redshift Clusters");
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
            RedshiftClient client = awsClientProvider.getRedshiftClient(region, account);
            String api = "RedshiftClient/describeClusters";
            DescribeClustersResponse resp = rateLimiter.doWithRateLimit(
                    api, ImmutableSortedMap.of(
                            SCRAPE_ACCOUNT_ID_LABEL, account.getAccountId(),
                            SCRAPE_REGION_LABEL, region,
                            SCRAPE_OPERATION_LABEL, api
                    ), client::describeClusters);
            if (resp.hasClusters()) {
                samples.addAll(resp.clusters().stream()
                        .map(cluster -> {
                            Map<String, String> labels = new TreeMap<>();
                            labels.put(SCRAPE_ACCOUNT_ID_LABEL, account.getAccountId());
                            labels.put(SCRAPE_REGION_LABEL, region);
                            labels.put("namespace", "AWS/Redshift");
                            labels.put("aws_resource_type", "AWS::Redshift::Cluster");
                            labels.put("job", cluster.clusterIdentifier());
                            labels.put("name", cluster.clusterIdentifier());
                            labels.put("id", cluster.clusterIdentifier());
                            labels.putAll(tagUtil.tagLabels(scrapeConfig, cluster.tags()
                                    .stream()
                                    .map(t -> Tag.builder().key(t.key()).value(t.value()).build())
                                    .collect(Collectors.toList())
                            ));
                            return sampleBuilder.buildSingleSample("aws_resource", labels, 1.0D);
                        })
                        .filter(Optional::isPresent)
                        .map(Optional::get)
                        .collect(Collectors.toList()));
            }
        } catch (Exception e) {
            log.error("Error:" + account, e);
        }
        return samples;
    }
}
