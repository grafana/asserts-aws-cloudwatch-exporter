/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.config;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@EqualsAndHashCode
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@ToString
@JsonIgnoreProperties(ignoreUnknown = true)
public class DimensionToLabel {
    private String namespace;
    /**
     * The dimension name. For e.g. for DynamoDB Table, the table name is present in the dimension
     * <code>TableName</code>
     */
    private String dimensionName;

    private static final String MATCH_ANY = "(.*)";
    private static final String PASS_THROUGH = "$0";
    private String regex = MATCH_ANY;
    private String valueExp = PASS_THROUGH;

    @JsonIgnore
    private Pattern pattern;

    /**
     * By default most resource names are mapped to the `job` label
     */
    private String mapToLabel = "job";

    private String entityType = "Service";

    public void compile() {
        pattern = Pattern.compile(regex);
    }

    public Optional<String> getValue(final String dimensionValue) {
        if (regex.equals(MATCH_ANY) && valueExp.equals(PASS_THROUGH)) {
            return Optional.of(dimensionValue);
        }
        String returnValue = valueExp;
        Matcher m = pattern.matcher(dimensionValue);
        if (m.matches()) {
            Map<String, String> groups = new TreeMap<>();
            for (int i = 0; i <= m.groupCount(); i++) {
                groups.put("$" + i, m.group(i));
            }
            for (String ref : groups.keySet()) {
                String replacement = groups.get(ref);
                returnValue = returnValue.replace(ref, replacement);
            }
            return Optional.of(returnValue);
        }
        return Optional.empty();
    }
}
