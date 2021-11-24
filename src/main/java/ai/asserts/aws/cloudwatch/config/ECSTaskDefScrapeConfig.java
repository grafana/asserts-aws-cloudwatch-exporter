/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.cloudwatch.config;

import ai.asserts.aws.resource.Resource;
import io.micrometer.core.instrument.util.StringUtils;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.With;

import static ai.asserts.aws.resource.ResourceType.ECSTaskDef;

@EqualsAndHashCode
@Getter
@SuppressWarnings("FieldMayBeFinal")
@NoArgsConstructor
@With
@AllArgsConstructor
public class ECSTaskDefScrapeConfig {
    private String taskDefinitionName;
    private String taskDefinitionVersion;
    private String containerDefinitionName;
    private Integer containerPort;
    private String metricPath;

    @EqualsAndHashCode
    @Getter
    @Builder
    public static class ScrapeTarget {
        private Integer containerPort;
        private String metricPath;
    }

    public boolean validate() {
        return StringUtils.isNotEmpty(taskDefinitionName) && (containerPort != null || metricPath != null);
    }

    public boolean isApplicable(Resource taskDefResource) {
        return taskDefResource.getType().equals(ECSTaskDef) &&
                taskDefResource.getName().equals(taskDefinitionName) &&
                (taskDefinitionVersion == null || taskDefResource.getVersion().equals(taskDefinitionVersion));
    }
}