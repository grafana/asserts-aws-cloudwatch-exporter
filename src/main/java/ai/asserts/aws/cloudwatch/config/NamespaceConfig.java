/*
 * Copyright © 2021
 * Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.cloudwatch.config;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.micrometer.core.instrument.util.StringUtils;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static java.lang.String.format;

@Getter
@Setter
@ToString
@EqualsAndHashCode
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NamespaceConfig {
    @JsonIgnore
    private ScrapeConfig scrapeConfig;
    private String name;
    private Integer period;
    private Integer scrapeInterval;
    private Set<String> dimensions;
    private List<MetricConfig> metrics;
    private List<LogScrapeConfig> logs;

    public void inheritAndValidate(int index) {

        List<String> errors = new ArrayList<>();
        if (StringUtils.isBlank(name)) {
            errors.add(format("namespace[%d].name not specified", index));
        }
        if (scrapeInterval != null && (scrapeInterval < 60 || scrapeInterval % 60 != 0)) {
            errors.add(format("namespace[%d].scrapeInterval has to be a multiple of 60", index));
        } else if (period != null && (period < 60 || period % 60 != 0)) {
            errors.add(format("namespace[%d].period has to be a multiple of 60", index));
        }
        if (errors.size() > 0) {
            throw new RuntimeException(String.join("\n", errors));
        }

        for (int j = 0; j < metrics.size(); j++) {
            MetricConfig metricConfig = metrics.get(j);
            metricConfig.setNamespace(this);
            metricConfig.validate(j);
        }

        if (!CollectionUtils.isEmpty(logs)) {
            logs.forEach(LogScrapeConfig::initalize);
        }
    }

    public Integer getScrapeInterval() {
        if(scrapeInterval!=null) {
            return scrapeInterval;
        } else {
            return scrapeConfig.getScrapeInterval();
        }
    }

    public Integer getPeriod() {
        if(period!=null) {
            return period;
        } else {
            return scrapeConfig.getPeriod();
        }
    }
}
