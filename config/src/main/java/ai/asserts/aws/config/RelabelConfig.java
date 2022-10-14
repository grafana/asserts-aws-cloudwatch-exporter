/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.config;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Getter
@Setter
@EqualsAndHashCode
@ToString
@Slf4j
@JsonIgnoreProperties(ignoreUnknown = true)
public class RelabelConfig {
    @JsonProperty("source_labels")
    private List<String> labels;
    private String regex;
    @EqualsAndHashCode.Exclude
    @JsonIgnore
    @ToString.Exclude
    private Pattern compiledExp;
    @JsonProperty("target_label")
    private String target;
    private String replacement;

    /**
     * Can be `drop-metric`, `keep-metric` or `replace`
     */
    private String action = "replace";

    private String metricName;

    public boolean actionReplace() {
        return replace();
    }

    public void validate() {
        if (!StringUtils.hasLength(regex)) {
            throw new RuntimeException("regex not specified in " + this);

        } else if (replace() && !StringUtils.hasLength(target)) {
            throw new RuntimeException("target_label not specified in " + this);
        } else if (replace() && !StringUtils.hasLength(replacement)) {
            throw new RuntimeException("replacement not specified in " + this);
        } else if (keepMetric() && !StringUtils.hasLength(metricName)) {
            throw new RuntimeException("metricName not specified in " + this);
        } else if (CollectionUtils.isEmpty(labels) || labels.stream().anyMatch(l -> !StringUtils.hasLength(l))) {
            throw new RuntimeException("source_labels not specified or has empty value " + this);
        }
        compiledExp = Pattern.compile(regex);
    }

    public Map<String, String> addReplacements(String metricName, Map<String, String> labelValues) {
        if (replace()) {
            Map<String, String> input = new TreeMap<>(labelValues);
            input.put("__name__", metricName);

            if (labels.stream().allMatch(input::containsKey)) {
                String source = labels.stream().map(label -> input.getOrDefault(label, ""))
                        .collect(Collectors.joining(";"));
                Matcher matcher = compiledExp.matcher(source);
                if (matcher.matches()) {
                    Map<String, String> result = new TreeMap<>(labelValues);
                    Map<String, String> capturingGroups = new TreeMap<>();
                    for (int i = 0; i <= matcher.groupCount(); i++) {
                        capturingGroups.put("$" + i + "", matcher.group(i));
                    }
                    String targetValue = replacement;
                    for (String group : capturingGroups.keySet()) {
                        targetValue = targetValue.replace(group, capturingGroups.get(group));
                    }
                    result.put(target, targetValue);
                    return result;
                }
            }
        }
        return labelValues;
    }

    public boolean dropMetric(String metricName, Map<String, String> labelValues) {
        if (!dropMetric() && !keepMetric()) {
            return false;
        }
        boolean matches = matches(metricName, labelValues);
        boolean explicitDrop = dropMetric() && matches;
        boolean dontKeep = keepMetric() && this.metricName.equals(metricName) && !matches;
        return explicitDrop || dontKeep;
    }

    private boolean replace() {
        return "replace".equals(action);
    }

    private boolean dropMetric() {
        return "drop-metric".equals(action) || "drop".equals(action);
    }

    private boolean keepMetric() {
        return action.equals("keep-metric") || action.equals("keep");
    }

    private boolean matches(String metricName, Map<String, String> labelValues) {
        Map<String, String> input = new TreeMap<>(labelValues);
        input.put("__name__", metricName);

        if (labels.stream().allMatch(input::containsKey)) {
            String source = labels.stream()
                    .map(label -> input.getOrDefault(label, ""))
                    .collect(Collectors.joining(";"));
            return compiledExp.matcher(source).matches();
        }
        return false;
    }
}
