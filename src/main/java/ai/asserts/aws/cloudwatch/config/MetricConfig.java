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
import software.amazon.awssdk.services.cloudwatch.model.Metric;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static java.lang.String.format;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode
public class MetricConfig {
    @JsonIgnore
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private NamespaceConfig namespace;
    private String name;
    /**
     * The time period for which the statistic needs to be computed.
     */
    private Integer period;
    private Integer scrapeInterval;
    private Set<MetricStat> stats;

    /**
     * The number of samples that will be returned in each scrape of this metric. This is determined by the
     * {@link #getScrapeInterval()} and {@link #getPeriod()}. If <code> period < scrapeInterval </code> then this
     * would be <code>scrapeInterval / period</code>. Else this would be just <code>1</code>
     *
     * @return The number of samples per scrape
     */
    public int numSamplesPerScrape() {
        return getScrapeInterval() > getPeriod() ? getScrapeInterval() / getPeriod() : 1;
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

    public boolean matchesMetric(Metric cwMetric) {
        return CollectionUtils.isEmpty(namespace.getDimensionFilterPattern()) ||
                (cwMetric.hasDimensions() && cwMetric.dimensions().stream()
                        .allMatch(this::matchesDimension));
    }

    void validate(int position) {
        List<String> errors = new ArrayList<>();
        if (StringUtils.isBlank(name)) {
            errors.add(format(
                    "metricConfigs[%d].name not specified for namespace '%s'",
                    position, namespace.getName()));
        }

        if (scrapeInterval != null && (scrapeInterval < 60 || scrapeInterval % 60 != 0)) {
            errors.add("metricConfigs[%d].scrapeInterval has to be a multiple of 60" +
                    Arrays.asList(MetricStat.values()));
        }

        if (period != null && (period < 60 || period % 60 != 0)) {
            errors.add("metricConfigs[%d].period has to be a multiple of 60" +
                    Arrays.asList(MetricStat.values()));
        }

        if (CollectionUtils.isEmpty(stats)) {
            errors.add("metricConfigs[%d].stats. At least one metric stat needs to specified. Valid values are " +
                    Arrays.asList(MetricStat.values()));
        }

        if (errors.size() > 0) {
            throw new RuntimeException(String.join("\n", errors));
        }
    }


    private boolean matchesDimension(software.amazon.awssdk.services.cloudwatch.model.Dimension dimension) {
        Map<String, Pattern> _dimensionFilterPattern = namespace.getDimensionFilterPattern();
        return !_dimensionFilterPattern.containsKey(dimension.name()) ||
                _dimensionFilterPattern.get(dimension.name()).matcher(dimension.value()).matches();
    }
}
