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

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

@Getter
@Setter
@EqualsAndHashCode(of = {"excludePattern", "includePattern"})
@NoArgsConstructor
public class TagExportConfig {
    private String excludePattern;
    private String includePattern;
    private Pattern exclude;
    private Pattern include;
    private List<String> tagsAsDisplayName;

    public void compile() {
        if (excludePattern != null) {
            exclude = Pattern.compile(excludePattern);
        }
        if (includePattern != null) {
            include = Pattern.compile(includePattern);
        }
    }

    public boolean shouldCaptureTag(Tag tag) {
        boolean _include = true;
        boolean _exclude = false;

        if (include != null) {
            _include = include.matcher(tag.key()).matches();
        }
        if (exclude != null) {
            _exclude = exclude.matcher(tag.key()).matches();
        }

        return _include && !_exclude;
    }

    public Map<String, String> tagLabels(List<Tag> tags, MetricNameUtil metricNameUtil) {
        Map<String, String> labels = new TreeMap<>();
        if (!CollectionUtils.isEmpty(tags)) {
            tags.stream()
                    .filter(this::shouldCaptureTag).forEach(t -> {
                String key = t.key();
                labels.put("tag_" + metricNameUtil.toSnakeCase(key), t.value());
                if (tagsAsDisplayName != null && tagsAsDisplayName.contains(key)) {
                    labels.put("display_name", t.value());
                }
            });
        }
        return labels;
    }
}
