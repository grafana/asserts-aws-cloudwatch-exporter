/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.exporter;

import ai.asserts.aws.AWSClientProvider;
import ai.asserts.aws.MetricNameUtil;
import ai.asserts.aws.AWSApiCallRateLimiter;
import ai.asserts.aws.ScrapeConfigProvider;
import ai.asserts.aws.SimpleTenantTask;
import ai.asserts.aws.TagUtil;
import ai.asserts.aws.TaskExecutorUtil;
import ai.asserts.aws.account.AWSAccount;
import ai.asserts.aws.account.AccountProvider;
import ai.asserts.aws.config.ScrapeConfig;
import ai.asserts.aws.resource.ResourceMapper;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;
import io.prometheus.client.Collector;
import io.prometheus.client.Collector.MetricFamilySamples.Sample;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.elasticloadbalancing.ElasticLoadBalancingClient;
import software.amazon.awssdk.services.elasticloadbalancing.model.DescribeLoadBalancersResponse;
import software.amazon.awssdk.services.elasticloadbalancing.model.DescribeTagsRequest;
import software.amazon.awssdk.services.elasticloadbalancing.model.DescribeTagsResponse;
import software.amazon.awssdk.services.elasticloadbalancing.model.LoadBalancerDescription;
import software.amazon.awssdk.services.elasticloadbalancingv2.ElasticLoadBalancingV2Client;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.LoadBalancer;
import software.amazon.awssdk.services.resourcegroupstaggingapi.model.Tag;
import software.amazon.awssdk.utils.Pair;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

import static ai.asserts.aws.MetricNameUtil.SCRAPE_ACCOUNT_ID_LABEL;
import static ai.asserts.aws.MetricNameUtil.SCRAPE_OPERATION_LABEL;
import static ai.asserts.aws.MetricNameUtil.SCRAPE_REGION_LABEL;
import static org.springframework.util.CollectionUtils.isEmpty;

@Component
@Slf4j
public class LoadBalancerExporter extends Collector implements MetricProvider {
    private final AccountProvider accountProvider;
    private final AWSClientProvider awsClientProvider;
    private final MetricSampleBuilder metricSampleBuilder;
    private final ResourceMapper resourceMapper;
    private final ScrapeConfigProvider scrapeConfigProvider;
    private final MetricNameUtil metricNameUtil;
    private final AWSApiCallRateLimiter rateLimiter;
    private final TagUtil tagUtil;
    private final TaskExecutorUtil taskExecutorUtil;

    private volatile List<Collector.MetricFamilySamples> resourceMetrics;

    public LoadBalancerExporter(AccountProvider accountProvider, AWSClientProvider awsClientProvider,
                                MetricSampleBuilder metricSampleBuilder, ResourceMapper resourceMapper,
                                ScrapeConfigProvider scrapeConfigProvider, MetricNameUtil metricNameUtil,
                                AWSApiCallRateLimiter rateLimiter, TagUtil tagUtil, TaskExecutorUtil taskExecutorUtil) {
        this.accountProvider = accountProvider;
        this.awsClientProvider = awsClientProvider;
        this.metricSampleBuilder = metricSampleBuilder;
        this.resourceMapper = resourceMapper;
        this.scrapeConfigProvider = scrapeConfigProvider;
        this.metricNameUtil = metricNameUtil;
        this.rateLimiter = rateLimiter;
        this.tagUtil = tagUtil;
        this.taskExecutorUtil = taskExecutorUtil;
        this.resourceMetrics = new ArrayList<>();
    }

    @Override
    public void update() {
        List<MetricFamilySamples> metricFamilySamples = new ArrayList<>();
        List<Sample> samples = new ArrayList<>();
        List<Sample> elbEC2RelSamples = new ArrayList<>();
        List<Future<Pair<List<Sample>, List<Sample>>>> futures = new ArrayList<>();
        accountProvider.getAccounts().forEach(awsAccount -> awsAccount.getRegions().forEach(region -> {
            ScrapeConfig scrapeConfig = scrapeConfigProvider.getScrapeConfig(awsAccount.getTenant());
            futures.add(taskExecutorUtil.executeAccountTask(awsAccount,
                    new SimpleTenantTask<Pair<List<Sample>, List<Sample>>>() {
                        @Override
                        public Pair<List<Sample>, List<Sample>> call() {
                            return buildSamples(awsAccount, region, scrapeConfig);
                        }
                    }));
        }));
        taskExecutorUtil.awaitAll(futures, (pair) -> {
            samples.addAll(pair.left());
            elbEC2RelSamples.addAll(pair.right());
        });
        metricSampleBuilder.buildFamily(samples).ifPresent(metricFamilySamples::add);
        metricSampleBuilder.buildFamily(elbEC2RelSamples).ifPresent(metricFamilySamples::add);
        resourceMetrics = metricFamilySamples;
    }

    private Pair<List<Sample>, List<Sample>> buildSamples(AWSAccount awsAccount, String region,
                                                          ScrapeConfig scrapeConfig) {
        List<Sample> samples = new ArrayList<>();
        List<Sample> elbEC2RelSamples = new ArrayList<>();
        log.info("Exporting Load Balancer resource metrics for {}-{}",
                awsAccount.getAccountId(),
                region);
        try {
            ElasticLoadBalancingClient elbClient =
                    awsClientProvider.getELBClient(region, awsAccount);
            DescribeLoadBalancersResponse resp = rateLimiter.doWithRateLimit(
                    "ElasticLoadBalancingClient/describeLoadBalancers",
                    ImmutableSortedMap.of(
                            SCRAPE_ACCOUNT_ID_LABEL, awsAccount.getAccountId(),
                            SCRAPE_REGION_LABEL, region,
                            SCRAPE_OPERATION_LABEL,
                            "ElasticLoadBalancingClient/describeLoadBalancers"
                    ),
                    elbClient::describeLoadBalancers);
            if (!isEmpty(resp.loadBalancerDescriptions())) {
                DescribeTagsResponse describeTagsResponse = rateLimiter.doWithRateLimit(
                        "ElasticLoadBalancingClient/describeTags",
                        ImmutableSortedMap.of(
                                SCRAPE_ACCOUNT_ID_LABEL, awsAccount.getAccountId(),
                                SCRAPE_REGION_LABEL, region,
                                SCRAPE_OPERATION_LABEL,
                                "ElasticLoadBalancingClient/describeTags"
                        ),
                        () -> elbClient.describeTags(DescribeTagsRequest.builder()
                                .loadBalancerNames(
                                        resp.loadBalancerDescriptions().stream()
                                                .map(LoadBalancerDescription::loadBalancerName)
                                                .collect(Collectors.toSet()))
                                .build()));
                Map<String, List<Tag>> classLBTagsByName = new TreeMap<>();
                describeTagsResponse.tagDescriptions().forEach(tagDescription ->
                        classLBTagsByName.put(tagDescription.loadBalancerName(),
                                tagDescription.tags().stream()
                                        .map(t -> Tag.builder()
                                                .key(t.key())
                                                .value(t.value())
                                                .build())
                                        .collect(Collectors.toList())));

                resp.loadBalancerDescriptions().forEach(lbDescription -> {
                    Map<String, String> labels = new TreeMap<>();
                    labels.put(SCRAPE_ACCOUNT_ID_LABEL, awsAccount.getAccountId());
                    labels.put(SCRAPE_REGION_LABEL, region);
                    labels.put("namespace", "AWS/ELB");
                    labels.put("aws_resource_type",
                            "AWS::ElasticLoadBalancing::LoadBalancer");
                    labels.put("job", lbDescription.loadBalancerName());
                    labels.put("name", lbDescription.loadBalancerName());
                    labels.put("id", lbDescription.loadBalancerName());
                    if (classLBTagsByName.containsKey(lbDescription.loadBalancerName())) {
                        List<Tag> allTags =
                                classLBTagsByName.get(lbDescription.loadBalancerName());

                        // This is for backward compatibility. We can modify model rule
                        // to instead
                        // use the
                        /// k8s_* series of labels
                        allTags.stream()
                                .filter(tag -> scrapeConfig.shouldExportTag(tag.key(),
                                        tag.value()))
                                .forEach(tag -> labels.put(
                                        metricNameUtil.toSnakeCase("tag_" + tag.key()),
                                        tag.value()));

                        allTags.stream().filter(tag -> tag.key()
                                        .equals("kubernetes.io/service-name"))
                                .findFirst().ifPresent(tag -> {
                                    String[] parts = tag.value().split("/");
                                    labels.put("k8s_namespace", parts[0]);
                                    labels.put("k8s_service", parts[1]);
                                });

                        allTags.stream().filter(tag -> tag.key()
                                        .startsWith("kubernetes.io/cluster"))
                                .findFirst().ifPresent(tag -> {
                                    String[] parts = tag.key().split("/");
                                    labels.put("k8s_cluster", parts[2]);
                                });

                        labels.putAll(tagUtil.tagLabels(scrapeConfig, allTags));
                    }
                    metricSampleBuilder.buildSingleSample("aws_resource", labels, 1.0D)
                            .ifPresent(samples::add);

                    if (!labels.containsKey("k8s_cluster")) {
                        discoverTargetEC2Instances(elbEC2RelSamples, awsAccount, region,
                                lbDescription);
                    }
                });
            }
        } catch (Exception e) {
            log.error("Failed to discover classic load balancers", e);
        }

        try {
            ElasticLoadBalancingV2Client elbClient =
                    awsClientProvider.getELBV2Client(region, awsAccount);
            software.amazon.awssdk.services.elasticloadbalancingv2.model.DescribeLoadBalancersResponse
                    resp =
                    rateLimiter.doWithRateLimit(
                            "ElasticLoadBalancingClient/describeLoadBalancers",
                            ImmutableSortedMap.of(
                                    SCRAPE_ACCOUNT_ID_LABEL, awsAccount.getAccountId(),
                                    SCRAPE_REGION_LABEL, region,
                                    SCRAPE_OPERATION_LABEL,
                                    "ElasticLoadBalancingClient/describeLoadBalancers"
                            ),
                            elbClient::describeLoadBalancers);
            if (resp.hasLoadBalancers()) {
                software.amazon.awssdk.services.elasticloadbalancingv2.model.DescribeTagsResponse
                        tagsResponse =
                        rateLimiter.doWithRateLimit(
                                "ElasticLoadBalancingClient/describeTags",
                                ImmutableSortedMap.of(
                                        SCRAPE_ACCOUNT_ID_LABEL, awsAccount.getAccountId(),
                                        SCRAPE_REGION_LABEL, region,
                                        SCRAPE_OPERATION_LABEL,
                                        "ElasticLoadBalancingClient/describeTags"
                                ),
                                () -> elbClient.describeTags(
                                        software.amazon.awssdk.services.elasticloadbalancingv2.model.DescribeTagsRequest.builder()
                                                .resourceArns(resp.loadBalancers().stream()
                                                        .map(LoadBalancer::loadBalancerArn)
                                                        .collect(Collectors.toList()))
                                                .build()));
                Map<String, List<Tag>> tagsByIdOrName = new TreeMap<>();

                tagsResponse.tagDescriptions()
                        .forEach(td -> resourceMapper.map(td.resourceArn())
                                .ifPresent(res -> {
                                    List<Tag> tags =
                                            tagsByIdOrName.computeIfAbsent(
                                                    res.getIdOrName(),
                                                    k -> new ArrayList<>());
                                    tags.addAll(td.tags().stream()
                                            .map(t -> Tag.builder()
                                                    .key(t.key())
                                                    .value(t.value()).build())
                                            .collect(Collectors.toList()));
                                }));

                resp.loadBalancers().stream()
                        .map(loadBalancer -> resourceMapper.map(
                                loadBalancer.loadBalancerArn()))
                        .filter(Optional::isPresent)
                        .map(Optional::get)
                        .forEach(resource -> {
                            Map<String, String> labels = new TreeMap<>();
                            labels.put(SCRAPE_ACCOUNT_ID_LABEL, awsAccount.getAccountId());
                            labels.put(SCRAPE_REGION_LABEL, region);
                            labels.put("aws_resource_type",
                                    "AWS::ElasticLoadBalancingV2::LoadBalancer");
                            labels.put("job", resource.getName());
                            labels.put("name", resource.getName());
                            labels.put("id", resource.getId());
                            labels.put("type", resource.getSubType());
                            if ("app".equals(resource.getSubType())) {
                                labels.put("namespace", "AWS/ApplicationELB");
                            } else {
                                labels.put("namespace", "AWS/NetworkELB");
                            }

                            if (tagsByIdOrName.containsKey(resource.getIdOrName())) {
                                labels.putAll(
                                        tagUtil.tagLabels(scrapeConfig, tagsByIdOrName.get(
                                                resource.getIdOrName())));
                            }

                            metricSampleBuilder.buildSingleSample("aws_resource", labels,
                                            1.0D)
                                    .ifPresent(samples::add);
                        });
            }
        } catch (Exception e) {
            log.error("Failed to discover ALBs and NLBs", e);
        }
        return Pair.of(samples, elbEC2RelSamples);
    }

    private void discoverTargetEC2Instances(List<Sample> elbEC2RelSamples, AWSAccount awsAccount, String region,
                                            LoadBalancerDescription loadBalancerDescription) {
        if (!isEmpty(loadBalancerDescription.instances())) {
            loadBalancerDescription.instances().forEach(instance -> {
                // For each listener, there will be a forwarding port which will forward to
                // all the instances.
                loadBalancerDescription.listenerDescriptions().forEach(lD -> {
                    Map<String, String> relLabels =
                            new HashMap<>(ImmutableMap.<String, String>builder()
                                    .put(SCRAPE_ACCOUNT_ID_LABEL, awsAccount.getAccountId())
                                    .put(SCRAPE_REGION_LABEL, region)
                                    .put("lb_name", loadBalancerDescription.loadBalancerName())
                                    .put("lb_type", "classic")
                                    .put("ec2_instance_id", instance.instanceId())
                                    .put("port", lD.listener().instancePort().toString())
                                    .build());

                    metricSampleBuilder.buildSingleSample(
                                    "aws_lb_to_ec2_instance", relLabels, 1.0D)
                            .ifPresent(elbEC2RelSamples::add);
                });
            });
        }
    }

    @Override
    public List<MetricFamilySamples> collect() {
        return resourceMetrics;
    }
}
