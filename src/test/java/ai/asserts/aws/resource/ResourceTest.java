/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.resource;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.cloudwatch.model.Dimension;
import software.amazon.awssdk.services.cloudwatch.model.Metric;

import static ai.asserts.aws.resource.ResourceType.S3Bucket;
import static ai.asserts.aws.resource.ResourceType.LambdaFunction;
import static ai.asserts.aws.resource.ResourceType.SQSQueue;
import static ai.asserts.aws.resource.ResourceType.DynamoDBTable;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ResourceTest {
    @Test
    public void matches_Lambda() {
        Resource resource = Resource.builder()
                .type(LambdaFunction)
                .name("function")
                .build();

        assertFalse(resource.matches(Metric.builder()
                .build()));
        assertFalse(resource.matches(Metric.builder()
                .dimensions(Dimension.builder()
                        .name("FunctionName").value("function1")
                        .build())
                .build()));
        assertTrue(resource.matches(Metric.builder()
                .dimensions(Dimension.builder()
                        .name("FunctionName").value("function")
                        .build())
                .build()));
    }

    @Test
    public void matches_SQSQueue() {
        Resource resource = Resource.builder()
                .type(SQSQueue)
                .name("queue")
                .build();

        assertFalse(resource.matches(Metric.builder()
                .build()));
        assertFalse(resource.matches(Metric.builder()
                .dimensions(Dimension.builder()
                        .name("QueueName").value("queue1")
                        .build())
                .build()));
        assertTrue(resource.matches(Metric.builder()
                .dimensions(Dimension.builder()
                        .name("QueueName").value("queue")
                        .build())
                .build()));
    }

    @Test
    public void matches_DynamoDBTable() {
        Resource resource = Resource.builder()
                .type(DynamoDBTable)
                .name("table")
                .build();

        assertFalse(resource.matches(Metric.builder()
                .build()));
        assertFalse(resource.matches(Metric.builder()
                .dimensions(Dimension.builder()
                        .name("TableName").value("table1")
                        .build())
                .build()));
        assertTrue(resource.matches(Metric.builder()
                .dimensions(Dimension.builder()
                        .name("TableName").value("table")
                        .build())
                .build()));
    }

    @Test
    public void matches_S3Bucket() {
        Resource resource = Resource.builder()
                .type(S3Bucket)
                .name("bucket")
                .build();

        assertFalse(resource.matches(Metric.builder()
                .build()));
        assertFalse(resource.matches(Metric.builder()
                .dimensions(Dimension.builder()
                        .name("BucketName").value("bucket1")
                        .build())
                .build()));
        assertTrue(resource.matches(Metric.builder()
                .dimensions(Dimension.builder()
                        .name("BucketName").value("bucket")
                        .build())
                .build()));
    }
}
