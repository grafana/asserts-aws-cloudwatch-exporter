/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.exporter;

import ai.asserts.aws.AWSClientProvider;
import ai.asserts.aws.RateLimiter;
import ai.asserts.aws.cloudwatch.config.ScrapeConfig;
import ai.asserts.aws.cloudwatch.config.ScrapeConfigProvider;
import ai.asserts.aws.resource.Resource;
import ai.asserts.aws.resource.ResourceMapper;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableSortedMap;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.elasticloadbalancingv2.ElasticLoadBalancingV2Client;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.DescribeListenersRequest;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.DescribeListenersResponse;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.DescribeLoadBalancersResponse;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.DescribeRulesRequest;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.DescribeRulesResponse;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.Listener;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.LoadBalancer;

import java.util.Map;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

import static ai.asserts.aws.MetricNameUtil.SCRAPE_OPERATION_LABEL;
import static ai.asserts.aws.MetricNameUtil.SCRAPE_REGION_LABEL;
import static org.springframework.util.CollectionUtils.isEmpty;

@Component
@Slf4j
@AllArgsConstructor
public class TargetGroupLBMapProvider {
    private final ScrapeConfigProvider scrapeConfigProvider;
    private final AWSClientProvider awsClientProvider;
    private final ResourceMapper resourceMapper;
    private final RateLimiter rateLimiter;
    @Getter
    private final Map<Resource, Resource> tgToLB = new ConcurrentHashMap<>();

    public void update() {
        try {
            ScrapeConfig scrapeConfig = scrapeConfigProvider.getScrapeConfig();
            scrapeConfig.getRegions().forEach(region -> {
                try (ElasticLoadBalancingV2Client lbClient = awsClientProvider.getELBV2Client(region)) {
                    String api = "ElasticLoadBalancingV2Client/describeLoadBalancers";
                    ImmutableSortedMap<String, String> labels = ImmutableSortedMap.of(
                            SCRAPE_REGION_LABEL, region, SCRAPE_OPERATION_LABEL, api);
                    DescribeLoadBalancersResponse resp = rateLimiter.doWithRateLimit(api, labels,
                            lbClient::describeLoadBalancers);
                    if (resp.hasLoadBalancers()) {
                        resp.loadBalancers().forEach(lb -> mapLB(lbClient, labels, lb));
                    }
                }
            });
        } catch (Exception e) {
            log.error("Failed to build LB Target Group map", e);
        }
    }

    @VisibleForTesting
    void mapLB(ElasticLoadBalancingV2Client lbClient, SortedMap<String, String> labels, LoadBalancer lb) {
        String lbArn = lb.loadBalancerArn();
        resourceMapper.map(lbArn).ifPresent(lbResource -> {
            String api = "ElasticLoadBalancingV2Client/describeListeners";
            SortedMap<String ,String> telemetryLabels = new TreeMap<>(labels);
            telemetryLabels.put(SCRAPE_OPERATION_LABEL, api);
            rateLimiter.doWithRateLimit(api, telemetryLabels, () -> {
                DescribeListenersRequest listenersRequest = DescribeListenersRequest.builder()
                        .loadBalancerArn(lbArn)
                        .build();
                DescribeListenersResponse listenersResponse = lbClient.describeListeners(listenersRequest);
                if (!isEmpty(listenersResponse.listeners())) {
                    listenersResponse.listeners()
                            .forEach(listener -> mapListener(lbClient, labels, lbResource, listener));
                }
                return null;
            });
        });
    }

    @VisibleForTesting
    void mapListener(ElasticLoadBalancingV2Client lbClient, SortedMap<String, String> labels, Resource lbResource,
                     Listener listener) {
        SortedMap<String ,String> telemetryLabels = new TreeMap<>(labels);
        telemetryLabels.put(SCRAPE_OPERATION_LABEL, "ElasticLoadBalancingClientV2/describeRules");
        DescribeRulesResponse dLR = rateLimiter.doWithRateLimit("ElasticLoadBalancingClientV2/describeRules",
                telemetryLabels,
                () -> lbClient.describeRules(DescribeRulesRequest.builder()
                        .listenerArn(listener.listenerArn())
                        .build()));
        if (dLR.hasRules()) {
            dLR.rules().stream()
                    .filter(rule -> !isEmpty(rule.actions()))
                    .flatMap(rule -> rule.actions().stream())
                    .filter(action -> action.targetGroupArn() != null)
                    .map(action -> resourceMapper.map(action.targetGroupArn()))
                    .filter(Optional::isPresent)
                    .map(Optional::get)
                    .forEach(tg -> tgToLB.put(tg, lbResource));
        }
    }
}
