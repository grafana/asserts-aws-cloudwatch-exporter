/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.exporter;

import ai.asserts.aws.AWSClientProvider;
import ai.asserts.aws.CollectionBuilderTask;
import ai.asserts.aws.AWSApiCallRateLimiter;
import ai.asserts.aws.TaskExecutorUtil;
import ai.asserts.aws.account.AWSAccount;
import ai.asserts.aws.account.AccountProvider;
import ai.asserts.aws.resource.ResourceMapper;
import com.google.common.collect.ImmutableSortedMap;
import io.prometheus.client.Collector;
import io.prometheus.client.Collector.MetricFamilySamples.Sample;
import io.prometheus.client.CollectorRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.services.kinesisanalyticsv2.KinesisAnalyticsV2Client;
import software.amazon.awssdk.services.kinesisanalyticsv2.model.ApplicationSummary;
import software.amazon.awssdk.services.kinesisanalyticsv2.model.ListApplicationsResponse;

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
public class KinesisAnalyticsExporter extends Collector implements InitializingBean {
    private final AccountProvider accountProvider;
    private final AWSClientProvider awsClientProvider;
    public final CollectorRegistry collectorRegistry;
    private final AWSApiCallRateLimiter rateLimiter;
    private final ResourceMapper resourceMapper;
    private final MetricSampleBuilder sampleBuilder;
    private final TaskExecutorUtil taskExecutorUtil;
    private volatile List<MetricFamilySamples> metricFamilySamples = new ArrayList<>();

    public KinesisAnalyticsExporter(AccountProvider accountProvider, AWSClientProvider awsClientProvider,
                                    CollectorRegistry collectorRegistry, ResourceMapper resourceMapper,
                                    AWSApiCallRateLimiter rateLimiter,
                                    MetricSampleBuilder sampleBuilder, TaskExecutorUtil taskExecutorUtil) {
        this.accountProvider = accountProvider;
        this.awsClientProvider = awsClientProvider;
        this.collectorRegistry = collectorRegistry;
        this.resourceMapper = resourceMapper;
        this.rateLimiter = rateLimiter;
        this.sampleBuilder = sampleBuilder;
        this.taskExecutorUtil = taskExecutorUtil;
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
        log.info("Exporting Kinesis Analytics Resources");
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
        List<Sample> samples = new ArrayList<>();
        KinesisAnalyticsV2Client client = awsClientProvider.getKAClient(region, account);
        String api = "KinesisAnalyticsV2Client/listApplications";
        ListApplicationsResponse resp = rateLimiter.doWithRateLimit(
                api, ImmutableSortedMap.of(
                        SCRAPE_ACCOUNT_ID_LABEL, account.getAccountId(),
                        SCRAPE_REGION_LABEL, region,
                        SCRAPE_OPERATION_LABEL, api
                ), client::listApplications);
        if (resp.hasApplicationSummaries()) {
            samples.addAll(resp.applicationSummaries().stream()
                    .map(ApplicationSummary::applicationARN)
                    .map(resourceMapper::map)
                    .filter(Optional::isPresent)
                    .map(Optional::get)
                    .map(resource -> {
                        Map<String, String> labels = new TreeMap<>();
                        resource.addLabels(labels, "");
                        labels.put("aws_resource_type", labels.get("type"));
                        if (StringUtils.hasLength(resource.getAccount())) {
                            labels.put(SCRAPE_ACCOUNT_ID_LABEL, resource.getAccount());
                            labels.remove("account");
                        }
                        labels.remove("type");
                        if (labels.containsKey("name")) {
                            labels.put("job", labels.get("name"));
                        }
                        labels.put("namespace", "AWS/KinesisAnalytics");
                        return sampleBuilder.buildSingleSample("aws_resource", labels, 1.0D);
                    })
                    .filter(Optional::isPresent)
                    .map(Optional::get)
                    .collect(Collectors.toList()));
        }
        return samples;
    }
}

