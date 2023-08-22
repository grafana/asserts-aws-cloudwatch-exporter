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
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.Bucket;
import software.amazon.awssdk.services.s3.model.ListBucketsResponse;

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
public class S3BucketExporter extends Collector implements InitializingBean {
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

    public S3BucketExporter(
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
        log.info("Exporting S3 Bucket Resources");
        List<MetricFamilySamples> newFamily = new ArrayList<>();
        List<Sample> allSamples = new ArrayList<>();
        List<Future<List<Sample>>> futures = new ArrayList<>();
        accountProvider.getAccounts().forEach(account -> account.getRegions().forEach(region ->
                futures.add(
                        taskExecutorUtil.executeAccountTask(account, new CollectionBuilderTask<Sample>() {
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
            S3Client client = awsClientProvider.getS3Client(region, account);
            String api = "S3Client/listBuckets";
            ListBucketsResponse resp = rateLimiter.doWithRateLimit(
                    api, ImmutableSortedMap.of(
                            SCRAPE_ACCOUNT_ID_LABEL, account.getAccountId(),
                            SCRAPE_REGION_LABEL, region,
                            SCRAPE_OPERATION_LABEL, api
                    ), client::listBuckets);
            if (resp.hasBuckets()) {
                Map<String, Resource> byName =
                        resourceTagHelper.getResourcesWithTag(account, region, "s3:bucket",
                                resp.buckets().stream().map(Bucket::name).collect(Collectors.toList()));
                List<Sample> regionSamples = resp.buckets().stream()
                        .map(bucket -> {
                            Map<String, String> labels = new TreeMap<>();
                            labels.put(SCRAPE_ACCOUNT_ID_LABEL, account.getAccountId());
                            labels.put(SCRAPE_REGION_LABEL, region);
                            labels.put("aws_resource_type", "AWS::S3::Bucket");
                            labels.put("job", bucket.name());
                            labels.put("name", bucket.name());
                            labels.put("id", bucket.name());
                            labels.put("namespace", "AWS/S3");
                            if (byName.containsKey(bucket.name())) {
                                labels.putAll(tagUtil.tagLabels(
                                        scrapeConfig, byName.get(bucket.name()).getTags()));
                            }
                            return sampleBuilder.buildSingleSample("aws_resource", labels, 1.0D);
                        })
                        .filter(Optional::isPresent)
                        .map(Optional::get)
                        .collect(Collectors.toList());
                samples.addAll(regionSamples);
            }
        } catch (Throwable e) {
            log.error("Error " + account, e);
        }
        return samples;
    }
}
