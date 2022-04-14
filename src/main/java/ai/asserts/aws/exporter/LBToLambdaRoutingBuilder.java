/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.exporter;

import ai.asserts.aws.AWSClientProvider;
import ai.asserts.aws.RateLimiter;
import ai.asserts.aws.ScrapeConfigProvider;
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

import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static ai.asserts.aws.MetricNameUtil.SCRAPE_ACCOUNT_ID_LABEL;
import static ai.asserts.aws.MetricNameUtil.SCRAPE_OPERATION_LABEL;
import static ai.asserts.aws.MetricNameUtil.SCRAPE_REGION_LABEL;
import static ai.asserts.aws.resource.ResourceType.LambdaFunction;
import static org.springframework.util.CollectionUtils.isEmpty;

@Component
@Slf4j
@AllArgsConstructor
public class LBToLambdaRoutingBuilder {
    private final AccountIDProvider accountIDProvider;
    private final ScrapeConfigProvider scrapeConfigProvider;
    private final AWSClientProvider awsClientProvider;
    private final RateLimiter rateLimiter;
    private final ResourceMapper resourceMapper;
    private final TargetGroupLBMapProvider targetGroupLBMapProvider;

    public Set<ResourceRelation> getRoutings() {
        log.info("LB To Lambda routing relation builder about to build relations");
        Set<ResourceRelation> routing = new HashSet<>();
        scrapeConfigProvider.getScrapeConfig().getRegions().forEach(region -> {
            try (ElasticLoadBalancingV2Client elbV2Client = awsClientProvider.getELBV2Client(region)) {
                Map<Resource, Resource> tgToLB = targetGroupLBMapProvider.getTgToLB();
                tgToLB.keySet().forEach(tg -> {
                    try {
                        String api = "ElasticLoadBalancingV2Client/describeTargetHealth";
                        DescribeTargetHealthResponse response = rateLimiter.doWithRateLimit(
                                api,
                                ImmutableSortedMap.of(
                                        SCRAPE_REGION_LABEL, region,
                                        SCRAPE_ACCOUNT_ID_LABEL, accountIDProvider.getAccountId(),
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
                    } catch (Exception e) {
                        log.error("Failed to build resource relations", e);
                    }
                });
            }
        });
        return routing;
    }
}
