/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.config;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.util.CollectionUtils;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode
@JsonIgnoreProperties(ignoreUnknown = true)
public class TagExportConfig {
    private Set<String> excludePatterns = new HashSet<>();
    private Set<String> excludeTags = new HashSet<>();
    private Set<String> includePatterns = new HashSet<>();
    private Set<String> includeTags = new HashSet<>();
    @EqualsAndHashCode.Exclude
    private Set<Pattern> _exclude = new HashSet<>();
    @EqualsAndHashCode.Exclude
    private Set<Pattern> _include = new HashSet<>();

    public void compile() {
        excludePatterns.forEach(pattern -> _exclude.add(Pattern.compile(pattern)));
        includePatterns.forEach(pattern -> _include.add(Pattern.compile(pattern)));
    }

    public boolean shouldCaptureTag(String tagName, String value) {
        Set<String> tagNames = new HashSet<>();
        if (CollectionUtils.isEmpty(includeTags) && CollectionUtils.isEmpty(includePatterns)) {
            tagNames.add(tagName);
        } else if (!CollectionUtils.isEmpty(includeTags) && includeTags.contains(tagName)) {
            tagNames.add(tagName);
        } else if (!CollectionUtils.isEmpty(includePatterns) &&
                _include.stream().anyMatch(p -> p.matcher(tagName).matches())) {
            tagNames.add(tagName);
        }

        if (!CollectionUtils.isEmpty(excludeTags) && excludeTags.contains(tagName)) {
            tagNames.remove(tagName);
        } else if (!CollectionUtils.isEmpty(excludePatterns) &&
                _exclude.stream().anyMatch(p -> p.matcher(tagName).matches())) {
            tagNames.remove(tagName);
        }

        return tagNames.size() > 0;
    }

    @JsonIgnore
    public Set<Pattern> get_exclude() {
        return _exclude;
    }

    @JsonIgnore
    public Set<Pattern> get_include() {
        return _include;
    }
}
