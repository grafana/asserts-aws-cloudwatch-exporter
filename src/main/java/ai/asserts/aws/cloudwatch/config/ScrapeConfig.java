
package ai.asserts.aws.cloudwatch.config;


import ai.asserts.aws.resource.Resource;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.util.CollectionUtils;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static ai.asserts.aws.cloudwatch.model.CWNamespace.ecs_containerinsights;
import static ai.asserts.aws.cloudwatch.model.CWNamespace.ecs_svc;
import static ai.asserts.aws.cloudwatch.model.CWNamespace.lambda;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@SuppressWarnings("FieldMayBeFinal")
public class ScrapeConfig {
    private Set<String> regions;
    private List<NamespaceConfig> namespaces;

    @Builder.Default
    private Integer scrapeInterval = 60;

    @Builder.Default
    private Integer period = 60;

    @Builder.Default
    private Integer delay = 0;

    @Builder.Default
    private Integer listMetricsResultCacheTTLMinutes = 10;

    @Builder.Default
    private Integer listFunctionsResultCacheTTLMinutes = 5;

    @Builder.Default
    private Integer getResourcesResultCacheTTLMinutes = 5;

    @Builder.Default
    private Integer numTaskThreads = 10;

    @Builder.Default
    private Integer awsAPICallsPerMinute = 30;

    private List<ECSTaskDefScrapeConfig> ecsTaskScrapeConfigs;

    public Optional<NamespaceConfig> getLambdaConfig() {
        if (CollectionUtils.isEmpty(namespaces)) {
            return Optional.empty();
        }
        return namespaces.stream()
                .filter(namespaceConfig -> lambda.isThisNamespace(namespaceConfig.getName()))
                .findFirst();
    }

    public boolean isECSMonitoringOn() {
        if (CollectionUtils.isEmpty(namespaces)) {
            return false;
        }
        return namespaces.stream()
                .anyMatch(nsConfig -> ecs_svc.isThisNamespace(nsConfig.getName()) ||
                        ecs_containerinsights.isThisNamespace(nsConfig.getName()));
    }

    public Optional<ECSTaskDefScrapeConfig> getECSScrapeConfig(Resource task) {
        if (CollectionUtils.isEmpty(ecsTaskScrapeConfigs)) {
            return Optional.empty();
        }
        return ecsTaskScrapeConfigs.stream()
                .filter(config -> config.isApplicable(task))
                .findFirst();
    }
}
