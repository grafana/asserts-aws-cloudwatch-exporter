/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.exporter;

import ai.asserts.aws.AWSApiCallRateLimiter;
import ai.asserts.aws.AWSClientProvider;
import ai.asserts.aws.SimpleTenantTask;
import ai.asserts.aws.TaskExecutorUtil;
import ai.asserts.aws.account.AWSAccount;
import ai.asserts.aws.account.AccountProvider;
import ai.asserts.aws.resource.Resource;
import ai.asserts.aws.resource.ResourceMapper;
import ai.asserts.aws.resource.ResourceRelation;
import com.google.common.collect.ImmutableSortedMap;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.elasticloadbalancingv2.ElasticLoadBalancingV2Client;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.DescribeTargetHealthRequest;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.DescribeTargetHealthResponse;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.TargetGroupNotFoundException;
import software.amazon.awssdk.utils.Pair;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Future;

import static ai.asserts.aws.MetricNameUtil.SCRAPE_ACCOUNT_ID_LABEL;
import static ai.asserts.aws.MetricNameUtil.SCRAPE_OPERATION_LABEL;
import static ai.asserts.aws.MetricNameUtil.SCRAPE_REGION_LABEL;
import static ai.asserts.aws.resource.ResourceType.LambdaFunction;
import static org.springframework.util.CollectionUtils.isEmpty;

@Component
@Slf4j
@AllArgsConstructor
public class LBToLambdaRoutingBuilder {
    private final AWSClientProvider awsClientProvider;
    private final AWSApiCallRateLimiter rateLimiter;
    private final ResourceMapper resourceMapper;
    private final AccountProvider accountProvider;
    private final TargetGroupLBMapProvider targetGroupLBMapProvider;
    private final TaskExecutorUtil taskExecutorUtil;

    public Set<ResourceRelation> getRoutings() {
        log.info("LB To Lambda routing relation builder about to build relations");
        Set<ResourceRelation> routing = new HashSet<>();
        Set<Resource> missingTgs = new HashSet<>();
        List<Future<Pair<Set<ResourceRelation>, Set<Resource>>>> futures = new ArrayList<>();
        for (AWSAccount accountRegion : accountProvider.getAccounts()) {
            accountRegion.getRegions().forEach(region ->
                    futures.add(taskExecutorUtil.executeAccountTask(accountRegion,
                            new SimpleTenantTask<Pair<Set<ResourceRelation>, Set<Resource>>>() {
                                @Override
                                public Pair<Set<ResourceRelation>, Set<Resource>> call() {
                                    return buildRelations(region, accountRegion);
                                }
                            })));
        }
        taskExecutorUtil.awaitAll(futures, (pair) -> {
            routing.addAll(pair.left());
            missingTgs.addAll(pair.right());
        });

        if (missingTgs.size() > 0) {
            targetGroupLBMapProvider.handleMissingTgs(missingTgs);
        }
        return routing;
    }

    private Pair<Set<ResourceRelation>, Set<Resource>> buildRelations(String region, AWSAccount accountRegion) {
        Set<ResourceRelation> routing = new HashSet<>();
        Set<Resource> missingTgs = new HashSet<>();
        try {
            ElasticLoadBalancingV2Client elbV2Client = awsClientProvider.getELBV2Client(region, accountRegion);
            Map<Resource, Resource> tgToLB = targetGroupLBMapProvider.getTgToLB();
            tgToLB.keySet().stream()
                    .filter(tg -> tg.getAccount().equals(accountRegion.getAccountId()) && region.equals(tg.getRegion()))
                    .forEach(tg -> {
                        try {
                            String api = "ElasticLoadBalancingV2Client/describeTargetHealth";
                            DescribeTargetHealthResponse response = rateLimiter.doWithRateLimit(
                                    api,
                                    ImmutableSortedMap.of(
                                            SCRAPE_REGION_LABEL, region,
                                            SCRAPE_ACCOUNT_ID_LABEL, accountRegion.getAccountId(),
                                            SCRAPE_OPERATION_LABEL, api
                                    )
                                    , () -> elbV2Client.describeTargetHealth(DescribeTargetHealthRequest.builder()
                                            .targetGroupArn(tg.getArn())
                                            .build()));
                            if (!isEmpty(response.targetHealthDescriptions())) {
                                response.targetHealthDescriptions().stream()
                                        .map(tH -> resourceMapper.map(tH.target().id()))
                                        .filter(opt -> opt.isPresent() && opt.get().getType().equals(LambdaFunction))
                                        .map(Optional::get)
                                        .forEach(lambda -> routing.add(ResourceRelation.builder()
                                                .from(tgToLB.get(tg))
                                                .to(lambda)
                                                .name("ROUTES_TO")
                                                .build()));
                            }
                        } catch (TargetGroupNotFoundException e) {
                            log.warn("LoadBalancer-2-TargetGroup Cache refers to non-existent TargetGroup {}", tg);
                            missingTgs.add(tg);
                        } catch (Exception e) {
                            if (e.getCause() instanceof TargetGroupNotFoundException) {
                                log.warn("LoadBalancer-2-TargetGroup Cache refers to non-existent TargetGroup {}", tg);
                                missingTgs.add(tg);
                            } else {
                                log.error("Failed to build resource relations", e);
                            }
                        }
                    });
        } catch (Exception e) {
            log.error("Error " + accountRegion, e);
        }
        return Pair.of(routing, missingTgs);
    }
}
