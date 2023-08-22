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
import ai.asserts.aws.resource.Resource;
import ai.asserts.aws.resource.ResourceMapper;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;
import io.prometheus.client.Collector;
import io.prometheus.client.Collector.MetricFamilySamples.Sample;
import io.prometheus.client.CollectorRegistry;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.elasticloadbalancingv2.ElasticLoadBalancingV2Client;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.DescribeListenersRequest;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.DescribeListenersResponse;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.DescribeLoadBalancersResponse;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.DescribeRulesRequest;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.DescribeRulesResponse;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.DescribeTargetGroupsRequest;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.DescribeTargetGroupsResponse;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.DescribeTargetHealthRequest;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.DescribeTargetHealthResponse;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.Listener;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.LoadBalancer;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.TargetDescription;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.TargetGroup;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;

import static ai.asserts.aws.MetricNameUtil.SCRAPE_ACCOUNT_ID_LABEL;
import static ai.asserts.aws.MetricNameUtil.SCRAPE_OPERATION_LABEL;
import static ai.asserts.aws.MetricNameUtil.SCRAPE_REGION_LABEL;
import static java.lang.String.format;
import static org.springframework.util.CollectionUtils.isEmpty;
import static org.springframework.util.StringUtils.hasLength;
import static software.amazon.awssdk.services.elasticloadbalancingv2.model.TargetHealthStateEnum.HEALTHY;
import static software.amazon.awssdk.services.elasticloadbalancingv2.model.TargetTypeEnum.INSTANCE;
import static software.amazon.awssdk.services.elasticloadbalancingv2.model.TargetTypeEnum.IP;

@Component
@Slf4j
public class TargetGroupLBMapProvider extends Collector implements InitializingBean {
    public static final String LB_EC2_INSTANCE_METRIC = "aws_lb_to_ec2_instance";
    private final AccountProvider accountProvider;
    private final AWSClientProvider awsClientProvider;
    private final ResourceMapper resourceMapper;
    private final AWSApiCallRateLimiter rateLimiter;
    private final MetricSampleBuilder sampleBuilder;
    private final CollectorRegistry collectorRegistry;
    private final TaskExecutorUtil taskExecutorUtil;
    @Getter
    private final Map<Resource, Resource> tgToLB = new ConcurrentHashMap<>();

    private final Map<Resource, Resource> missingTgMap = new ConcurrentHashMap<>();

    @Getter
    private volatile MetricFamilySamples metricFamilySamples = null;

    public TargetGroupLBMapProvider(AccountProvider accountProvider, AWSClientProvider awsClientProvider,
                                    ResourceMapper resourceMapper, AWSApiCallRateLimiter rateLimiter,
                                    MetricSampleBuilder sampleBuilder, CollectorRegistry collectorRegistry,
                                    TaskExecutorUtil taskExecutorUtil) {
        this.accountProvider = accountProvider;
        this.awsClientProvider = awsClientProvider;
        this.resourceMapper = resourceMapper;
        this.rateLimiter = rateLimiter;
        this.sampleBuilder = sampleBuilder;
        this.collectorRegistry = collectorRegistry;
        this.taskExecutorUtil = taskExecutorUtil;
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        collectorRegistry.register(this);
    }

    @Override
    public List<MetricFamilySamples> collect() {
        if (metricFamilySamples != null) {
            return ImmutableList.of(metricFamilySamples);
        }
        return Collections.emptyList();
    }

    public void update() {
        log.info("Updating TargetGroup to LoadBalancer map");
        List<Sample> newSamples = new ArrayList<>();
        List<Future<List<Sample>>> futures = new ArrayList<>();
        for (AWSAccount accountRegion : accountProvider.getAccounts()) {
            accountRegion.getRegions()
                    .forEach(region -> futures.add(taskExecutorUtil.executeAccountTask(accountRegion,
                            new CollectionBuilderTask<Sample>() {
                                @Override
                                public List<Sample> call() {
                                    return buildSamples(region, accountRegion);
                                }
                            })));
        }
        taskExecutorUtil.awaitAll(futures, newSamples::addAll);
        sampleBuilder.buildFamily(newSamples).ifPresent(familySamples -> metricFamilySamples = familySamples);
    }

    private List<Sample> buildSamples(String region, AWSAccount accountRegion) {
        List<Sample> newSamples = new ArrayList<>();
        try {
            ElasticLoadBalancingV2Client lbClient = awsClientProvider.getELBV2Client(region, accountRegion);
            String api = "ElasticLoadBalancingV2Client/describeLoadBalancers";
            ImmutableSortedMap<String, String> labels = ImmutableSortedMap.of(
                    SCRAPE_ACCOUNT_ID_LABEL, accountRegion.getAccountId(),
                    SCRAPE_REGION_LABEL, region, SCRAPE_OPERATION_LABEL, api);
            DescribeLoadBalancersResponse resp = rateLimiter.doWithRateLimit(api, labels,
                    lbClient::describeLoadBalancers);
            if (resp.hasLoadBalancers()) {
                resp.loadBalancers().forEach(lb -> newSamples.addAll(mapLB(lbClient, labels, lb)));
            }
        } catch (Exception e) {
            log.error("Failed to build LB Target Group map", e);
        }
        return newSamples;
    }

    public void handleMissingTgs(Set<Resource> missingTgs) {
        missingTgs.forEach(tg -> {
            missingTgMap.put(tg, tg);
            tgToLB.remove(tg);
        });
    }

    @VisibleForTesting
    List<Sample> mapLB(ElasticLoadBalancingV2Client lbClient,
                       SortedMap<String, String> labels, LoadBalancer lb) {
        List<Sample> lbEC2RelationSamples = new ArrayList<>();
        String lbArn = lb.loadBalancerArn();
        resourceMapper.map(lbArn).ifPresent(lbResource -> {
            String api = "ElasticLoadBalancingV2Client/describeListeners";
            SortedMap<String, String> telemetryLabels = new TreeMap<>(labels);
            telemetryLabels.put(SCRAPE_OPERATION_LABEL, api);
            rateLimiter.doWithRateLimit(api, telemetryLabels, () -> {
                DescribeListenersRequest listenersRequest = DescribeListenersRequest.builder()
                        .loadBalancerArn(lbArn)
                        .build();
                DescribeListenersResponse listenersResponse = lbClient.describeListeners(listenersRequest);
                if (!isEmpty(listenersResponse.listeners())) {
                    listenersResponse.listeners()
                            .forEach(listener -> lbEC2RelationSamples.addAll(mapListener(
                                    lbClient, labels, lbResource, listener)));
                }
                return null;
            });
        });
        return lbEC2RelationSamples;
    }

    @VisibleForTesting
    public Map<Resource, Resource> getMissingTgMap() {
        return missingTgMap;
    }

    @VisibleForTesting
    List<Sample> mapListener(ElasticLoadBalancingV2Client lbClient,
                             SortedMap<String, String> labels,
                             Resource lbResource,
                             Listener listener) {
        List<Sample> lbEC2RelationSamples = new ArrayList<>();
        SortedMap<String, String> telemetryLabels = new TreeMap<>(labels);
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
                    .forEach(action -> {
                        resourceMapper.map(action.targetGroupArn()).ifPresent(tg -> {
                            if (!missingTgMap.containsKey(tg)) {
                                tgToLB.put(tg, lbResource);
                            }
                        });
                        // If the TG has EC2 instances directly registered into it instead of through an ASG
                        // Build the LB-EC2 Relationship
                        String describeGroups = "ElasticLoadBalancingV2Client/describeTargetGroups";
                        telemetryLabels.put(SCRAPE_OPERATION_LABEL,
                                describeGroups);
                        DescribeTargetGroupsResponse tgr =
                                rateLimiter.doWithRateLimit(describeGroups, telemetryLabels,
                                        () -> lbClient.describeTargetGroups(DescribeTargetGroupsRequest.builder()
                                                .targetGroupArns(action.targetGroupArn())
                                                .build()));
                        if (!isEmpty(tgr.targetGroups())) {
                            tgr.targetGroups()
                                    .stream()
                                    .filter(this::filterTGs)
                                    .forEach(targetGroup -> {
                                        String api_describeHealth = "ElasticLoadBalancingV2Client/describeTargetHealth";
                                        telemetryLabels.put(SCRAPE_OPERATION_LABEL, api_describeHealth);
                                        DescribeTargetHealthResponse thr =
                                                rateLimiter.doWithRateLimit(
                                                        api_describeHealth,
                                                        telemetryLabels,
                                                        () -> lbClient.describeTargetHealth(
                                                                DescribeTargetHealthRequest.builder()
                                                                        .targetGroupArn(action.targetGroupArn())
                                                                        .build()));
                                        if (!isEmpty(thr.targetHealthDescriptions())) {
                                            thr.targetHealthDescriptions()
                                                    .stream()
                                                    .filter(thD -> thD.targetHealth().state().equals(HEALTHY))
                                                    .forEach(targetHealthDescription -> {
                                                        TargetDescription target = targetHealthDescription.target();
                                                        String id = target.id();
                                                        Map<String, String> relLabels =
                                                                new HashMap<>(ImmutableMap.<String, String>builder()
                                                                        .put(SCRAPE_ACCOUNT_ID_LABEL,
                                                                                lbResource.getAccount())
                                                                        .put(SCRAPE_REGION_LABEL,
                                                                                lbResource.getRegion())
                                                                        .put("lb_id", lbResource.getId())
                                                                        .put("lb_name", lbResource.getName())
                                                                        .put("lb_type",
                                                                                hasLength(lbResource.getSubType()) ?
                                                                                        lbResource.getSubType() :
                                                                                        "classic")
                                                                        .build());
                                                        // If it is an IP
                                                        if (id.split("\\.").length == 4) {
                                                            relLabels.put("instance", format("%s:%d", target.id(),
                                                                    target.port()));
                                                        } else {
                                                            relLabels.put("ec2_instance_id", target.id());
                                                            if (target.port() != null) {
                                                                relLabels.put("port", target.port().toString());
                                                            }
                                                        }
                                                        sampleBuilder.buildSingleSample(
                                                                        LB_EC2_INSTANCE_METRIC, relLabels, 1.0D)
                                                                .ifPresent(lbEC2RelationSamples::add);
                                                    });
                                        }
                                    });
                        }
                    });
        }
        return lbEC2RelationSamples;
    }

    private boolean filterTGs(TargetGroup targetGroup) {
        return targetGroup.targetType().equals(INSTANCE) || targetGroup.targetType().equals(IP);
    }
}
