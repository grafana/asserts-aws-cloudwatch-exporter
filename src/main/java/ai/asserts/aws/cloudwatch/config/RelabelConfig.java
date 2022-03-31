/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.cloudwatch.config;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.micrometer.core.instrument.util.StringUtils;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.springframework.util.CollectionUtils;

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

    public void validate() {
        if (StringUtils.isEmpty(regex)) {
            throw new RuntimeException("regex not specified in " + this);
        } else if (StringUtils.isEmpty(target)) {
            throw new RuntimeException("target_label not specified in " + this);
        } else if (StringUtils.isEmpty(replacement)) {
            throw new RuntimeException("replacement not specified in " + this);
        } else if (CollectionUtils.isEmpty(labels) || labels.stream().anyMatch(StringUtils::isEmpty)) {
            throw new RuntimeException("labels not specified or has empty value " + this);
        }
        compiledExp = Pattern.compile(regex);
    }

    public Map<String, String> addReplacements(String metricName, Map<String, String> labelValues) {
        Map<String, String> input = new TreeMap<>(labelValues);
        input.put("__name__", metricName);

        if (!labels.stream().allMatch(input::containsKey)) {
            return labelValues;
        }

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
        return labelValues;
    }
}
