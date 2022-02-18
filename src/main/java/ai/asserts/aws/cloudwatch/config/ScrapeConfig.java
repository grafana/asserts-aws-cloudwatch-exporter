package ai.asserts.aws.cloudwatch.config;


import ai.asserts.aws.resource.Resource;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.util.CollectionUtils;
import software.amazon.awssdk.services.ecs.model.ContainerDefinition;
import software.amazon.awssdk.services.ecs.model.TaskDefinition;
import software.amazon.awssdk.services.resourcegroupstaggingapi.model.Tag;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import static ai.asserts.aws.cloudwatch.model.CWNamespace.lambda;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
@SuppressWarnings("FieldMayBeFinal")
public class ScrapeConfig {
    @Setter
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
    @Setter
    private boolean discoverECSTasks = false;

    @Builder.Default
    private Set<String> discoverResourceTypes = new TreeSet<>();

    private List<ECSTaskDefScrapeConfig> ecsTaskScrapeConfigs;

    private TagExportConfig tagExportConfig;

    private String alertForwardUrl;

    private String tenant;

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

    public Set<String> getRegions() {
        return regions;
    }

    public boolean isDiscoverECSTasks() {
        return discoverECSTasks;
    }

    public boolean shouldExportTag(Tag tag) {
        if (tagExportConfig != null) {
            return tagExportConfig.shouldCaptureTag(tag);
        }
        return true;
    }

    public void setEnvTag(Resource resource) {
        if (tagExportConfig != null) {
            resource.setEnvTag(tagExportConfig.getEnvTag(resource.getTags()));
        }
    }
}
