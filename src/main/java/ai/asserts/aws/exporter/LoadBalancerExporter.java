/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.exporter;

import ai.asserts.aws.AWSClientProvider;
import ai.asserts.aws.AccountProvider;
import ai.asserts.aws.MetricNameUtil;
import ai.asserts.aws.RateLimiter;
import ai.asserts.aws.ScrapeConfigProvider;
import ai.asserts.aws.config.ScrapeConfig;
import ai.asserts.aws.resource.ResourceMapper;
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
import software.amazon.awssdk.services.resourcegroupstaggingapi.model.Tag;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
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
    private final RateLimiter rateLimiter;

    private volatile List<Collector.MetricFamilySamples> resourceMetrics;

    public LoadBalancerExporter(AccountProvider accountProvider, AWSClientProvider awsClientProvider,
                                MetricSampleBuilder metricSampleBuilder, ResourceMapper resourceMapper,
                                ScrapeConfigProvider scrapeConfigProvider, MetricNameUtil metricNameUtil,
                                RateLimiter rateLimiter) {
        this.accountProvider = accountProvider;
        this.awsClientProvider = awsClientProvider;
        this.metricSampleBuilder = metricSampleBuilder;
        this.resourceMapper = resourceMapper;
        this.scrapeConfigProvider = scrapeConfigProvider;
        this.metricNameUtil = metricNameUtil;
        this.rateLimiter = rateLimiter;
        this.resourceMetrics = new ArrayList<>();
    }

    @Override
    public void update() {
        List<MetricFamilySamples> metricFamilySamples = new ArrayList<>();
        List<Sample> samples = new ArrayList<>();
        ScrapeConfig scrapeConfig = scrapeConfigProvider.getScrapeConfig();
        accountProvider.getAccounts().forEach(awsAccount -> awsAccount.getRegions().forEach(region -> {
            log.info("Exporting Load Balancer resource metrics for {}-{}", awsAccount.getAccountId(), region);
            try (ElasticLoadBalancingClient elbClient = awsClientProvider.getELBClient(region, awsAccount)) {
                DescribeLoadBalancersResponse resp = rateLimiter.doWithRateLimit(
                        "ElasticLoadBalancingClient/describeLoadBalancers",
                        ImmutableSortedMap.of(
                                SCRAPE_ACCOUNT_ID_LABEL, awsAccount.getAccountId(),
                                SCRAPE_REGION_LABEL, region,
                                SCRAPE_OPERATION_LABEL, "ElasticLoadBalancingClient/describeLoadBalancers"
                        ),
                        elbClient::describeLoadBalancers);
                if (!isEmpty(resp.loadBalancerDescriptions())) {
                    DescribeTagsResponse describeTagsResponse = rateLimiter.doWithRateLimit(
                            "ElasticLoadBalancingClient/describeLoadBalancers",
                            ImmutableSortedMap.of(
                                    SCRAPE_ACCOUNT_ID_LABEL, awsAccount.getAccountId(),
                                    SCRAPE_REGION_LABEL, region,
                                    SCRAPE_OPERATION_LABEL, "ElasticLoadBalancingClient/describeTags"
                            ),
                            () -> elbClient.describeTags(DescribeTagsRequest.builder()
                                    .loadBalancerNames(
                                            resp.loadBalancerDescriptions().stream()
                                                    .map(LoadBalancerDescription::loadBalancerName)
                                                    .collect(Collectors.toSet()))
                                    .build()));
                    Map<String, List<Tag>> classLBTagsByName = new TreeMap<>();
                    describeTagsResponse.tagDescriptions().forEach(tagDescription ->
                            classLBTagsByName.put(tagDescription.loadBalancerName(), tagDescription.tags().stream()
                                    .map(t -> Tag.builder()
                                            .key(t.key())
                                            .value(t.value())
                                            .build())
                                    .collect(Collectors.toList())));

                    resp.loadBalancerDescriptions().forEach(loadBalancerDescription -> {
                        Map<String, String> labels = new TreeMap<>();
                        labels.put(SCRAPE_ACCOUNT_ID_LABEL, awsAccount.getAccountId());
                        labels.put(SCRAPE_REGION_LABEL, region);
                        labels.put("namespace", "AWS/ELB");
                        labels.put("aws_resource_type", "AWS::ElasticLoadBalancing::LoadBalancer");
                        labels.put("job", loadBalancerDescription.loadBalancerName());
                        labels.put("name", loadBalancerDescription.loadBalancerName());
                        labels.put("id", loadBalancerDescription.loadBalancerName());
                        if (classLBTagsByName.containsKey(loadBalancerDescription.loadBalancerName())) {
                            List<Tag> allTags = classLBTagsByName.get(loadBalancerDescription.loadBalancerName());

                            // This is for backward compatibility. We can modify model rule to instead use the
                            /// k8s_* series of labels
                            allTags.stream().filter(tag -> scrapeConfig.shouldExportTag(tag.key(), tag.value()))
                                    .forEach(tag -> labels.put(metricNameUtil.toSnakeCase("tag_" + tag.key()), tag.value()));

                            allTags.stream().filter(tag -> tag.key().equals("kubernetes.io/service-name"))
                                    .findFirst().ifPresent(tag -> {
                                String[] parts = tag.value().split("/");
                                labels.put("k8s_namespace", parts[0]);
                                labels.put("k8s_service", parts[1]);
                            });

                            allTags.stream().filter(tag -> tag.key().startsWith("kubernetes.io/cluster"))
                                    .findFirst().ifPresent(tag -> {
                                String[] parts = tag.key().split("/");
                                labels.put("k8s_cluster", parts[2]);
                            });
                        }
                        samples.add(metricSampleBuilder.buildSingleSample(
                                "aws_resource", labels, 1.0D));
                    });
                }
            } catch (Exception e) {
                log.error("Failed to discover classic load balancers", e);
            }

            try (ElasticLoadBalancingV2Client elbClient = awsClientProvider.getELBV2Client(region, awsAccount)) {
                software.amazon.awssdk.services.elasticloadbalancingv2.model.DescribeLoadBalancersResponse resp =
                        rateLimiter.doWithRateLimit("ElasticLoadBalancingClient/describeLoadBalancers",
                                ImmutableSortedMap.of(
                                        SCRAPE_ACCOUNT_ID_LABEL, awsAccount.getAccountId(),
                                        SCRAPE_REGION_LABEL, region,
                                        SCRAPE_OPERATION_LABEL, "ElasticLoadBalancingClient/describeLoadBalancers"
                                ),
                                elbClient::describeLoadBalancers);
                if (resp.hasLoadBalancers()) {
                    resp.loadBalancers().stream()
                            .map(loadBalancer -> resourceMapper.map(loadBalancer.loadBalancerArn()))
                            .filter(Optional::isPresent)
                            .map(Optional::get)
                            .forEach(resource -> {
                                Map<String, String> labels = new TreeMap<>();
                                labels.put(SCRAPE_ACCOUNT_ID_LABEL, awsAccount.getAccountId());
                                labels.put(SCRAPE_REGION_LABEL, region);
                                labels.put("aws_resource_type", "AWS::ElasticLoadBalancingV2::LoadBalancer");
                                labels.put("job", resource.getName());
                                labels.put("name", resource.getName());
                                labels.put("id", resource.getId());
                                labels.put("type", resource.getSubType());
                                if ("app".equals(resource.getSubType())) {
                                    labels.put("namespace", "AWS/ApplicationELB");
                                } else {
                                    labels.put("namespace", "AWS/NetworkELB");
                                }
                                samples.add(metricSampleBuilder.buildSingleSample(
                                        "aws_resource", labels, 1.0D));
                            });
                }
            } catch (Exception e) {
                log.error("Failed to discover ALBs and NLBs", e);
            }
        }));
        metricFamilySamples.add(metricSampleBuilder.buildFamily(samples));
        resourceMetrics = metricFamilySamples;
    }

    @Override
    public List<MetricFamilySamples> collect() {
        return resourceMetrics;
    }
}
