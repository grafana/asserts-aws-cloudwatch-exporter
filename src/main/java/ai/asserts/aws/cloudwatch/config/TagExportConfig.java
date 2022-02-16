/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.cloudwatch.config;

import ai.asserts.aws.MetricNameUtil;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.util.CollectionUtils;
import software.amazon.awssdk.services.resourcegroupstaggingapi.model.Tag;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

@Getter
@Setter
@NoArgsConstructor
public class TagExportConfig {
    private Set<String> excludePatterns = new HashSet<>();
    private Set<String> excludeTags = new HashSet<>();
    private Set<String> includePatterns = new HashSet<>();
    private Set<String> includeTags = new HashSet<>();
    private Set<String> envTags = new HashSet<>();
    @EqualsAndHashCode.Exclude
    private Set<Pattern> _exclude = new HashSet<>();
    @EqualsAndHashCode.Exclude
    private Set<Pattern> _include = new HashSet<>();

    public void compile() {
        excludePatterns.forEach(pattern -> _exclude.add(Pattern.compile(pattern)));
        includePatterns.forEach(pattern -> _include.add(Pattern.compile(pattern)));
    }

    public boolean shouldCaptureTag(Tag tag) {
        Set<String> tagNames = new HashSet<>();
        String tagName = tag.key();
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

    public Optional<Tag> getEnvTag(List<Tag> tags) {
        if (!CollectionUtils.isEmpty(tags) && !CollectionUtils.isEmpty(envTags)) {
            return tags.stream().filter(tag -> envTags.contains(tag.key())).findFirst();
        }
        return Optional.empty();
    }

    public Map<String, String> tagLabels(List<Tag> tags, MetricNameUtil metricNameUtil) {
        Map<String, String> labels = new TreeMap<>();
        if (!CollectionUtils.isEmpty(tags)) {
            tags.stream()
                    .filter(this::shouldCaptureTag).forEach(t -> {
                String key = t.key();
                labels.put("tag_" + metricNameUtil.toSnakeCase(key), t.value());
            });
        }
        return labels;
    }
}
