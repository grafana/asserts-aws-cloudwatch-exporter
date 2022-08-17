/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws;

import ai.asserts.aws.config.ScrapeConfig;
import ai.asserts.aws.resource.Resource;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import software.amazon.awssdk.services.resourcegroupstaggingapi.model.Tag;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

@Component
@AllArgsConstructor
public class TagUtil {
    private final ScrapeConfigProvider scrapeConfigProvider;
    private final MetricNameUtil metricNameUtil;

    public Map<String, String> tagLabels(List<Tag> tags) {
        ScrapeConfig scrapeConfig = scrapeConfigProvider.getScrapeConfig();
        Map<String, String> labels = new TreeMap<>();
        if (!CollectionUtils.isEmpty(tags)) {
            tags.stream()
                    .filter(t -> scrapeConfig.shouldExportTag(t.key(), t.value()))
                    .forEach(t -> {
                        String key = t.key();
                        labels.put("tag_" + metricNameUtil.toSnakeCase(key), t.value());
                    });
        }
        return labels;
    }
}
