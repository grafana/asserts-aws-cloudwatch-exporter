/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.exporter;

import ai.asserts.aws.AWSClientProvider;
import ai.asserts.aws.CollectionBuilderTask;
import ai.asserts.aws.MetricNameUtil;
import ai.asserts.aws.AWSApiCallRateLimiter;
import ai.asserts.aws.ScrapeConfigProvider;
import ai.asserts.aws.TagUtil;
import ai.asserts.aws.TaskExecutorUtil;
import ai.asserts.aws.account.AWSAccount;
import ai.asserts.aws.account.AccountProvider;
import ai.asserts.aws.config.ScrapeConfig;
import ai.asserts.aws.resource.Resource;
import ai.asserts.aws.resource.ResourceMapper;
import ai.asserts.aws.resource.ResourceTagHelper;
import com.google.common.collect.ImmutableSortedMap;
import io.prometheus.client.Collector;
import io.prometheus.client.Collector.MetricFamilySamples.Sample;
import io.prometheus.client.CollectorRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.ListQueuesResponse;

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
public class SQSQueueExporter extends Collector implements InitializingBean {
    private final AccountProvider accountProvider;
    private final AWSClientProvider awsClientProvider;
    public final CollectorRegistry collectorRegistry;
    private final AWSApiCallRateLimiter rateLimiter;
    private final ResourceMapper resourceMapper;
    private final MetricSampleBuilder sampleBuilder;
    private final MetricNameUtil metricNameUtil;
    private final ResourceTagHelper resourceTagHelper;
    private final TagUtil tagUtil;
    private final TaskExecutorUtil taskExecutorUtil;
    private final ScrapeConfigProvider scrapeConfigProvider;
    private volatile List<MetricFamilySamples> metricFamilySamples = new ArrayList<>();

    public SQSQueueExporter(
            AccountProvider accountProvider, AWSClientProvider awsClientProvider, CollectorRegistry collectorRegistry,
            ResourceMapper resourceMapper, AWSApiCallRateLimiter rateLimiter, MetricSampleBuilder sampleBuilder,
            MetricNameUtil metricNameUtil, ResourceTagHelper resourceTagHelper, TagUtil tagUtil,
            TaskExecutorUtil taskExecutorUtil, ScrapeConfigProvider scrapeConfigProvider) {
        this.accountProvider = accountProvider;
        this.awsClientProvider = awsClientProvider;
        this.collectorRegistry = collectorRegistry;
        this.resourceMapper = resourceMapper;
        this.rateLimiter = rateLimiter;
        this.sampleBuilder = sampleBuilder;
        this.metricNameUtil = metricNameUtil;
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
        log.info("Exporting SQS Queue Resources");
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
            SqsClient client = awsClientProvider.getSqsClient(region, account);
            String api = "SQSClient/listQueues";
            ListQueuesResponse resp = rateLimiter.doWithRateLimit(
                    api, ImmutableSortedMap.of(
                            SCRAPE_ACCOUNT_ID_LABEL, account.getAccountId(),
                            SCRAPE_REGION_LABEL, region,
                            SCRAPE_OPERATION_LABEL, api
                    ), client::listQueues);
            if (resp.hasQueueUrls()) {
                Map<String, Resource> byName =
                        resourceTagHelper.getResourcesWithTag(account, region, "sqs:queue",
                                resp.queueUrls().stream()
                                        .map(resourceMapper::map)
                                        .filter(Optional::isPresent)
                                        .map(opt -> opt.get().getName())
                                        .collect(Collectors.toList()));
                List<Sample> regionQueues = resp.queueUrls().stream()
                        .map(resourceMapper::map)
                        .filter(Optional::isPresent)
                        .map(Optional::get)
                        .map(resource -> {
                            Map<String, String> labels = new TreeMap<>();
                            resource.addTagLabels(labels, metricNameUtil);
                            labels.put(SCRAPE_ACCOUNT_ID_LABEL, account.getAccountId());
                            labels.put(SCRAPE_REGION_LABEL, resource.getRegion());
                            labels.put("namespace", "AWS/SQS");
                            labels.put("name", resource.getName());
                            labels.put("topic", resource.getName());
                            labels.put("aws_resource_type", "AWS::SQS::Queue");
                            if (StringUtils.hasLength(resource.getAccount())) {
                                labels.put(SCRAPE_ACCOUNT_ID_LABEL, resource.getAccount());
                                labels.remove("account");
                            }
                            labels.remove("type");
                            if (labels.containsKey("name")) {
                                labels.put("job", labels.get("name"));
                            }
                            if (byName.containsKey(resource.getName())) {
                                labels.putAll(tagUtil.tagLabels(scrapeConfig,
                                        byName.get(resource.getName()).getTags()));
                            }
                            return sampleBuilder.buildSingleSample("aws_resource", labels, 1.0D);
                        })
                        .filter(Optional::isPresent)
                        .map(Optional::get)
                        .collect(Collectors.toList());
                samples.addAll(regionQueues);
            }
        } catch (Throwable e) {
            log.error("Failed to discover queues", e);
        }
        return samples;
    }
}

