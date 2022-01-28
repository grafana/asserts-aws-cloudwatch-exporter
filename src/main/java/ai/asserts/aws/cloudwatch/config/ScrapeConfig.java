
package ai.asserts.aws.cloudwatch.config;


import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.util.CollectionUtils;
import software.amazon.awssdk.services.ecs.model.ContainerDefinition;
import software.amazon.awssdk.services.ecs.model.TaskDefinition;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

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
    private Integer delay = 0;

    @Builder.Default
    private Integer listMetricsResultCacheTTLMinutes = 10;

    @Builder.Default
    private Integer listFunctionsResultCacheTTLMinutes = 5;

    @Builder.Default
    private Integer getResourcesResultCacheTTLMinutes = 5;

    @Builder.Default
    private Integer numTaskThreads = 5;

    @Builder.Default
    private String ecsTargetSDFile = "ecs-task-scrape-targets.yml";

    @Builder.Default
    private Integer logScrapeDelaySeconds = 15;

    @Builder.Default
    private boolean discoverECSTasks = false;

    @Builder.Default
    private Set<String> discoverResourceTypes = new TreeSet<>();

    private List<ECSTaskDefScrapeConfig> ecsTaskScrapeConfigs;

    public Optional<NamespaceConfig> getLambdaConfig() {
        if (CollectionUtils.isEmpty(namespaces)) {
            return Optional.empty();
        }
        return namespaces.stream()
                .filter(namespaceConfig -> lambda.isThisNamespace(namespaceConfig.getName()))
                .findFirst();
    }

    public Optional<ECSTaskDefScrapeConfig> getECSScrapeConfig(TaskDefinition task) {
        if (CollectionUtils.isEmpty(ecsTaskScrapeConfigs)) {
            return Optional.empty();
        }
        return ecsTaskScrapeConfigs.stream()
                .filter(config -> task.hasContainerDefinitions() && task.containerDefinitions().stream()
                        .map(ContainerDefinition::name)
                        .anyMatch(name -> name.equals(config.getContainerDefinitionName())))
                .findFirst();
    }
}
