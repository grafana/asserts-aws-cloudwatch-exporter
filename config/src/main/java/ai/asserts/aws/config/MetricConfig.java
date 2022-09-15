
package ai.asserts.aws.config;

import ai.asserts.aws.model.MetricStat;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.services.cloudwatch.model.Metric;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode
@ToString
@JsonIgnoreProperties(ignoreUnknown = true)
public class MetricConfig {
    @JsonIgnore
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private NamespaceConfig namespace;
    private String name;
    private Integer scrapeInterval;
    private Set<MetricStat> stats;

    @JsonIgnore
    public Integer getEffectiveScrapeInterval() {
        if (scrapeInterval != null) {
            return scrapeInterval;
        } else {
            return namespace.getEffectiveScrapeInterval();
        }
    }

    public boolean matchesMetric(Metric cwMetric) {
        Map<String, String> dimensionValues = new TreeMap<>();
        cwMetric.dimensions().forEach(d -> dimensionValues.put(d.name(), d.value()));
        return CollectionUtils.isEmpty(namespace.getDimensionFilterPattern()) ||
                namespace.getDimensionFilterPattern().entrySet().stream()
                        .allMatch(entry -> entry.getValue().matches(dimensionValues.get(entry.getKey())));
    }

    void validate(int position) {
        List<String> errors = new ArrayList<>();
        if (!StringUtils.hasLength(name)) {
            errors.add(String.format(
                    "metricConfigs[%d].name not specified for namespace '%s'",
                    position, namespace.getName()));
        }

        if (scrapeInterval != null && (scrapeInterval < 60 || scrapeInterval % 60 != 0)) {
            errors.add("metricConfigs[%d].scrapeInterval has to be a multiple of 60" +
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
}
