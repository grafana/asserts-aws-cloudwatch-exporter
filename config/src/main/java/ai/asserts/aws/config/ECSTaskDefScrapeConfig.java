/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.config;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.With;
import org.springframework.util.StringUtils;

@EqualsAndHashCode
@Getter
@SuppressWarnings("FieldMayBeFinal")
@NoArgsConstructor
@With
@AllArgsConstructor
@ToString
@JsonIgnoreProperties(ignoreUnknown = true)
public class ECSTaskDefScrapeConfig {
    private String containerDefinitionName;
    private Integer containerPort;
    private String metricPath;

    @EqualsAndHashCode
    @Getter
    @Builder
    @ToString
    public static class ScrapeTarget {
        private Integer containerPort;
        private String metricPath;
    }

    @JsonIgnore
    public boolean validate() {
        return StringUtils.hasLength(containerDefinitionName) &&
                (containerPort != null || StringUtils.hasLength(metricPath));
    }
}
