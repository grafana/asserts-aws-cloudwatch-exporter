/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.config;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.collect.ImmutableSet;
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
     * Can be `drop-metric` or `replace`
     */
    private String action = "replace";

    public boolean actionReplace() {
        return "replace".equals(action);
    }

    public boolean actionDropMetric() {
        return "drop-metric".equals(action);
    }

    public void validate() {
        if (!StringUtils.hasLength(regex)) {
            throw new RuntimeException("regex not specified in " + this);
        } else if (!StringUtils.hasLength(target)) {
            throw new RuntimeException("target_label not specified in " + this);
        } else if (!StringUtils.hasLength(replacement)) {
            throw new RuntimeException("replacement not specified in " + this);
        } else if (CollectionUtils.isEmpty(labels) || labels.stream().anyMatch(l -> !StringUtils.hasLength(l))) {
            throw new RuntimeException("labels not specified or has empty value " + this);
        }
        compiledExp = Pattern.compile(regex);
        ImmutableSet<String> allowedActions = ImmutableSet.of("replace", "drop-metric");
        if (!allowedActions.contains(action)) {
            log.error("{}: Invalid value for 'action': {}, allowed values are {}", this, action, allowedActions);
        }
    }

    public Map<String, String> addReplacements(String metricName, Map<String, String> labelValues) {
        Map<String, String> input = new TreeMap<>(labelValues);
        input.put("__name__", metricName);

        if (labels.stream().allMatch(labelValues::containsKey)) {
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
        return labelValues;
    }

    public boolean matches(String metricName, Map<String, String> labelValues) {
        Map<String, String> input = new TreeMap<>(labelValues);
        input.put("__name__", metricName);

        if (labels.stream().anyMatch(key -> !labelValues.containsKey(key))) {
            return false;
        }

        String source = labels.stream().map(label -> input.getOrDefault(label, ""))
                .collect(Collectors.joining(";"));
        Matcher matcher = compiledExp.matcher(source);
        return matcher.matches();
    }
}
