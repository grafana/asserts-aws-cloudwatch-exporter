/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.cloudwatch.config;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

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

    public void compile() {
        if (regex != null) {
            compiledExp = Pattern.compile(regex);
        }
    }

    public Map<String, String> buildReplacements(String metricName, Map<String, String> labelValues) {
        Map<String, String> input = new TreeMap<>(labelValues);
        input.put("__name__", metricName);
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
