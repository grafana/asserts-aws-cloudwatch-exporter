/*
 *  Copyright © 2020.
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
import software.amazon.awssdk.services.firehose.FirehoseClient;
import software.amazon.awssdk.services.firehose.model.DescribeDeliveryStreamRequest;
import software.amazon.awssdk.services.firehose.model.DescribeDeliveryStreamResponse;
import software.amazon.awssdk.services.firehose.model.ListDeliveryStreamsResponse;

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
public class KinesisFirehoseExporter extends Collector implements InitializingBean {
    private final AccountProvider accountProvider;
    private final AWSClientProvider awsClientProvider;
    public final CollectorRegistry collectorRegistry;
    private final AWSApiCallRateLimiter rateLimiter;
    private final ResourceMapper resourceMapper;
    private final MetricSampleBuilder sampleBuilder;
    private final ResourceTagHelper resourceTagHelper;
    private final TagUtil tagUtil;
    private final TaskExecutorUtil taskExecutorUtil;
    private final ScrapeConfigProvider scrapeConfigProvider;
    private volatile List<MetricFamilySamples> metricFamilySamples = new ArrayList<>();

    public KinesisFirehoseExporter(
            AccountProvider accountProvider, AWSClientProvider awsClientProvider, CollectorRegistry collectorRegistry,
            ResourceMapper resourceMapper, AWSApiCallRateLimiter rateLimiter, MetricSampleBuilder sampleBuilder,
            ResourceTagHelper resourceTagHelper, TagUtil tagUtil, TaskExecutorUtil taskExecutorUtil,
            ScrapeConfigProvider scrapeConfigProvider) {
        this.accountProvider = accountProvider;
        this.awsClientProvider = awsClientProvider;
        this.collectorRegistry = collectorRegistry;
        this.resourceMapper = resourceMapper;
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
        log.info("Exporting Kinesis Firehose Resources");
        List<MetricFamilySamples> newFamily = new ArrayList<>();
        List<Sample> allSamples = new ArrayList<>();
        List<Future<List<Sample>>> futures = new ArrayList<>();
        accountProvider.getAccounts().forEach(account -> account.getRegions().forEach(region ->
                futures.add(
                        taskExecutorUtil.executeAccountTask(account, new CollectionBuilderTask<Sample>() {
                            @Override
                            public List<Sample> call() {
                                return buildMetricSamples(region, account);
                            }
                        }))));
        taskExecutorUtil.awaitAll(futures, allSamples::addAll);
        sampleBuilder.buildFamily(allSamples).ifPresent(newFamily::add);
        metricFamilySamples = newFamily;
    }

    private List<Sample> buildMetricSamples(String region, AWSAccount account) {
        ScrapeConfig scrapeConfig = scrapeConfigProvider.getScrapeConfig(account.getTenant());
        List<Sample> samples = new ArrayList<>();
        try {
            FirehoseClient client = awsClientProvider.getFirehoseClient(region, account);
            String api = "FirehoseClient/listDeliveryStreams";
            ListDeliveryStreamsResponse resp = rateLimiter.doWithRateLimit(
                    api, ImmutableSortedMap.of(
                            SCRAPE_ACCOUNT_ID_LABEL, account.getAccountId(),
                            SCRAPE_REGION_LABEL, region,
                            SCRAPE_OPERATION_LABEL, api
                    ), client::listDeliveryStreams);
            if (resp.hasDeliveryStreamNames()) {
                Map<String, Resource> byName = resourceTagHelper.getResourcesWithTag(account, region,
                        "firehose:deliverystream", resp.deliveryStreamNames());
                samples.addAll(resp.deliveryStreamNames().stream()
                        .map(name -> {
                            DescribeDeliveryStreamResponse streamResp = rateLimiter.doWithRateLimit(
                                    "FirehoseClient/describeDeliveryStream", ImmutableSortedMap.of(
                                            SCRAPE_ACCOUNT_ID_LABEL, account.getAccountId(),
                                            SCRAPE_REGION_LABEL, region,
                                            SCRAPE_OPERATION_LABEL, "FirehoseClient/describeDeliveryStream"
                                    ), () -> {
                                        DescribeDeliveryStreamRequest req =
                                                DescribeDeliveryStreamRequest.builder()
                                                        .deliveryStreamName(name)
                                                        .build();
                                        return client.describeDeliveryStream(req);
                                    });
                            return resourceMapper.map(
                                    streamResp.deliveryStreamDescription().deliveryStreamARN());
                        })
                        .filter(Optional::isPresent)
                        .map(Optional::get)
                        .map(resource -> {
                            Map<String, String> labels = new TreeMap<>();
                            resource.addLabels(labels, "");
                            labels.put("aws_resource_type", labels.get("type"));
                            labels.put("namespace", "AWS/Firehose");
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
                        .collect(Collectors.toList()));
            }
        } catch (Exception e) {
            log.error("Error " + account, e);
        }
        return samples;
    }
}

