/*
 * Copyright © 2021
 * Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.resource;

import ai.asserts.aws.MetricNameUtil;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.springframework.util.CollectionUtils;
import software.amazon.awssdk.services.cloudwatch.model.Dimension;
import software.amazon.awssdk.services.cloudwatch.model.Metric;
import software.amazon.awssdk.services.resourcegroupstaggingapi.model.Tag;

import java.util.List;
import java.util.Map;

import static java.lang.String.format;

@Getter
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@Builder
@ToString
public class Resource {
    @EqualsAndHashCode.Include
    private ResourceType type;
    private String arn;
    @EqualsAndHashCode.Include
    private String name;
    @EqualsAndHashCode.Include
    private String region;

    @Setter
    private List<Tag> tags;

    public boolean matches(Metric metric) {
        return metric.hasDimensions() && metric.dimensions().stream().anyMatch(this::matches);
    }

    boolean matches(Dimension dimension) {
        return dimension.name().equals(metricDimensionName()) && name.equals(dimension.value());
    }

    public void addLabels(Map<String, String> labels, String prefix) {
        labels.put(format("%s_type", prefix), type.name());
        labels.put(format("%s_name", prefix), name);
    }

    public void addTagLabels(Map<String, String> labels, MetricNameUtil metricNameUtil) {
        if (!CollectionUtils.isEmpty(tags)) {
            tags.forEach(tag -> labels.put(format("tag_%s", metricNameUtil.toSnakeCase(tag.key())), tag.value()));
        }
    }

    private String metricDimensionName() {
        switch (type) {
            case LambdaFunction:
                return "FunctionName";
            case SQSQueue:
                return "QueueName";
            case DynamoDBTable:
                return "TableName";
            case S3Bucket:
                return "BucketName";
            default:
                return "Unknown";
        }
    }
}
