/*
 * Copyright © 2021
 * Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.resource;

import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import software.amazon.awssdk.services.cloudwatch.model.Dimension;
import software.amazon.awssdk.services.cloudwatch.model.Metric;
import software.amazon.awssdk.services.resourcegroupstaggingapi.model.Tag;

import java.util.List;

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

    @Setter
    private List<Tag> tags;

    public boolean matches(Metric metric) {
        return metric.hasDimensions() && metric.dimensions().stream().anyMatch(this::matches);
    }

    boolean matches(Dimension dimension) {
        return dimension.name().equals(dimensionName()) && name.equals(dimension.value());
    }

    private String dimensionName() {
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
