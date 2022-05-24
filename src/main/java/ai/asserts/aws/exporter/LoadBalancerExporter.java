/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.exporter;

import ai.asserts.aws.AWSClientProvider;
import ai.asserts.aws.AccountProvider;
import ai.asserts.aws.RateLimiter;
import ai.asserts.aws.resource.ResourceMapper;
import com.google.common.collect.ImmutableSortedMap;
import io.prometheus.client.Collector;
import io.prometheus.client.Collector.MetricFamilySamples.Sample;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.elasticloadbalancing.ElasticLoadBalancingClient;
import software.amazon.awssdk.services.elasticloadbalancing.model.DescribeLoadBalancersResponse;
import software.amazon.awssdk.services.elasticloadbalancingv2.ElasticLoadBalancingV2Client;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import static ai.asserts.aws.MetricNameUtil.SCRAPE_ACCOUNT_ID_LABEL;
import static ai.asserts.aws.MetricNameUtil.SCRAPE_OPERATION_LABEL;
import static ai.asserts.aws.MetricNameUtil.SCRAPE_REGION_LABEL;

@Component
@Slf4j
public class LoadBalancerExporter extends Collector implements MetricProvider {
    private final AccountProvider accountProvider;
    private final AWSClientProvider awsClientProvider;
    private final MetricSampleBuilder metricSampleBuilder;
    private final ResourceMapper resourceMapper;
    private final RateLimiter rateLimiter;

    private volatile List<Collector.MetricFamilySamples> resourceMetrics;

    public LoadBalancerExporter(AccountProvider accountProvider, AWSClientProvider awsClientProvider,
                                MetricSampleBuilder metricSampleBuilder, ResourceMapper resourceMapper,
                                RateLimiter rateLimiter) {
        this.accountProvider = accountProvider;
        this.awsClientProvider = awsClientProvider;
        this.metricSampleBuilder = metricSampleBuilder;
        this.resourceMapper = resourceMapper;
        this.rateLimiter = rateLimiter;
        this.resourceMetrics = new ArrayList<>();
    }

    @Override
    public void update() {
        List<MetricFamilySamples> metricFamilySamples = new ArrayList<>();
        List<Sample> samples = new ArrayList<>();
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
                if (resp.hasLoadBalancerDescriptions()) {
                    resp.loadBalancerDescriptions().forEach(loadBalancerDescription -> {
                        Map<String, String> labels = new TreeMap<>();
                        labels.put(SCRAPE_ACCOUNT_ID_LABEL, awsAccount.getAccountId());
                        labels.put(SCRAPE_REGION_LABEL, region);
                        labels.put("aws_resource_type", "AWS::ElasticLoadBalancing::LoadBalancer");
                        labels.put("job", loadBalancerDescription.loadBalancerName());
                        labels.put("name", loadBalancerDescription.loadBalancerName());
                        labels.put("id", loadBalancerDescription.loadBalancerName());
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
                                labels.put("subtype", resource.getSubType());
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
