package ai.asserts.aws.cloudwatch.config;


import ai.asserts.aws.resource.Resource;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.google.common.annotations.VisibleForTesting;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.springframework.util.CollectionUtils;
import software.amazon.awssdk.services.ecs.model.ContainerDefinition;
import software.amazon.awssdk.services.ecs.model.TaskDefinition;
import software.amazon.awssdk.services.resourcegroupstaggingapi.model.Tag;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static ai.asserts.aws.cloudwatch.model.CWNamespace.lambda;
import static org.springframework.util.StringUtils.hasLength;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
@SuppressWarnings("FieldMayBeFinal")
@EqualsAndHashCode
@ToString
public class ScrapeConfig {
    @Setter
    @Builder.Default
    private Set<String> regions = new HashSet<>();

    @Builder.Default
    private List<NamespaceConfig> namespaces = new ArrayList<>();

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

    @Builder.Default
    private List<ECSTaskDefScrapeConfig> ecsTaskScrapeConfigs = new ArrayList<>();

    private TagExportConfig tagExportConfig;

    private String alertForwardUrl;

    private String tenant;

    private String assumeRole;

    @Builder.Default
    private List<RelabelConfig> relabelConfigs = new ArrayList<>();

    @Builder.Default
    private List<DimensionToLabel> dimensionToLabels = new ArrayList<>();

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
        return false;
    }

    public void setEnvTag(Resource resource) {
        if (tagExportConfig != null) {
            resource.setEnvTag(tagExportConfig.getEnvTag(resource.getTags()));
        }
    }

    public Map<String, String> getEntityLabels(String namespace, Map<String, String> alarmDimensions) {
        boolean unknownNamespace = !dimensionToLabels.stream()
                .map(DimensionToLabel::getNamespace)
                .collect(Collectors.toSet())
                .contains(namespace);

        boolean knownDimension = dimensionToLabels.stream()
                .map(DimensionToLabel::getDimensionName)
                .collect(Collectors.toSet())
                .stream().anyMatch(alarmDimensions::containsKey);

        SortedMap<String, String> labels = new TreeMap<>();
        dimensionToLabels.stream()
                .filter(dimensionToLabel -> captureDimension(namespace, alarmDimensions, dimensionToLabel))
                .forEach(dimensionToLabel -> mapTypeAndName(alarmDimensions, labels, dimensionToLabel));


        if (unknownNamespace && knownDimension) {
            dimensionToLabels.stream()
                    .filter(d -> alarmDimensions.containsKey(d.getDimensionName()))
                    .findFirst().ifPresent(dimensionToLabel -> {
                mapTypeAndName(alarmDimensions, labels, dimensionToLabel);
                labels.put("namespace", dimensionToLabel.getNamespace());
            });
        }

        return labels;
    }

    private void mapTypeAndName(Map<String, String> alarmDimensions, SortedMap<String, String> labels, DimensionToLabel dimensionToLabel) {
        String toLabel = dimensionToLabel.getMapToLabel();
        String dimensionName = dimensionToLabel.getDimensionName();
        labels.put(toLabel, alarmDimensions.get(dimensionName));
        if (hasLength(dimensionToLabel.getEntityType())) {
            labels.put("asserts_entity_type", dimensionToLabel.getEntityType());
        }
    }

    private boolean captureDimension(String namespace, Map<String, String> alarmDimensions,
                                     DimensionToLabel dimensionToLabel) {
        return (dimensionToLabel.getNamespace().equals(namespace)) &&
                alarmDimensions.containsKey(dimensionToLabel.getDimensionName());
    }

    @VisibleForTesting
    public void validateConfig() {
        if (!CollectionUtils.isEmpty(getNamespaces())) {
            for (int i = 0; i < getNamespaces().size(); i++) {
                NamespaceConfig namespaceConfig = getNamespaces().get(i);
                namespaceConfig.setScrapeConfig(this);
                namespaceConfig.validate(i);
            }
        }

        if (!CollectionUtils.isEmpty(getEcsTaskScrapeConfigs())) {
            getEcsTaskScrapeConfigs().forEach(ECSTaskDefScrapeConfig::validate);
        }

        if (getTagExportConfig() != null) {
            getTagExportConfig().compile();
        }

        relabelConfigs.forEach(RelabelConfig::compile);
    }

    public Map<String, String> applyRelabels(String metricName, Map<String, String> inputLabels) {
        Map<String, String> labels = inputLabels;
        for (RelabelConfig config : relabelConfigs) {
            labels = config.buildReplacements(metricName, labels);
        }
        return labels;
    }
}


