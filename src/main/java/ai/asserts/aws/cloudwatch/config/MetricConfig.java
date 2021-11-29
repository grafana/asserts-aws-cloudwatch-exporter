
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
@ToString
public class MetricConfig {
    @JsonIgnore
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private NamespaceConfig namespace;
    private String name;
    private Integer scrapeInterval;
    private Set<MetricStat> stats;

    public Integer getScrapeInterval() {
        if (scrapeInterval != null) {
            return scrapeInterval;
        } else {
            return namespace.getScrapeInterval();
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
        return _dimensionFilterPattern.containsKey(dimension.name()) &&
                _dimensionFilterPattern.get(dimension.name()).matcher(dimension.value()).matches();
    }
}
