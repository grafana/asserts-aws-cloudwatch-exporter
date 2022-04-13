/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.config;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.With;
import org.springframework.util.StringUtils;

@EqualsAndHashCode
@Getter
@SuppressWarnings("FieldMayBeFinal")
@NoArgsConstructor
@With
@AllArgsConstructor
public class ECSTaskDefScrapeConfig {
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
        return StringUtils.hasLength(containerDefinitionName) &&
                (containerPort != null || StringUtils.hasLength(metricPath));
    }
}
