/*
 * Copyright © 2021
 * Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.cloudwatch.config;

import ai.asserts.aws.cloudwatch.model.MetricStat;
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
import software.amazon.awssdk.services.cloudwatch.model.Dimension;
import software.amazon.awssdk.services.cloudwatch.model.Metric;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static java.lang.String.format;

@EqualsAndHashCode
@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MetricConfig {
    @JsonIgnore
    private NamespaceConfig namespace;
    private String name;
    /**
     * The time period for which the statistic needs to be computed.
     */
    private Integer period;
    private Integer scrapeInterval;
    private Set<MetricStat> stats;
    private Set<String> dimensions;

    public int numSamplesPerScrape() {
        return getScrapeInterval() > getPeriod() ? getScrapeInterval() / getPeriod() : 1;
    }

    public boolean matchesMetric(Metric cwMetric) {
        return CollectionUtils.isEmpty(dimensions) ||
                cwMetric.dimensions().stream()
                        .map(Dimension::name)
                        .collect(Collectors.toSet())
                        .containsAll(dimensions);
    }

    void validate(int position) {
        List<String> errors = new ArrayList<>();
        if (StringUtils.isBlank(name)) {
            errors.add(format(
                    "metricConfigs[%d].name not specified for namespace '%s'",
                    position, namespace.getName()));
        }
        if (CollectionUtils.isEmpty(stats)) {
            errors.add("metricConfigs[%d].stats. At least one metric stat needs to specified. Valid values are " +
                    Arrays.asList(MetricStat.values()));
        }
        if (scrapeInterval != null && (scrapeInterval < 60 || scrapeInterval % 60 != 0)) {
            errors.add("metricConfigs[%d].scrapeInterval has to be a multiple of 60" +
                    Arrays.asList(MetricStat.values()));
        }

        if (period != null && (period < 60 || period % 60 != 0)) {
            errors.add("metricConfigs[%d].period has to be a multiple of 60" +
                    Arrays.asList(MetricStat.values()));
        }

        if (errors.size() > 0) {
            throw new RuntimeException(String.join("\n", errors));
        }
    }

    public Integer getScrapeInterval() {
        if (scrapeInterval != null) {
            return scrapeInterval;
        } else {
            return namespace.getScrapeInterval();
        }
    }

    public Integer getPeriod() {
        if (period != null) {
            return period;
        } else {
            return namespace.getPeriod();
        }
    }

    public Set<String> getDimensions() {
        if (CollectionUtils.isEmpty(dimensions)) {
            return namespace.getDimensions();
        } else {
            return dimensions;
        }
    }
}
