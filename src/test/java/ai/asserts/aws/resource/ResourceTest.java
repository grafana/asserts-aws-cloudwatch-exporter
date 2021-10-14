/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.resource;

import ai.asserts.aws.MetricNameUtil;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.easymock.EasyMockSupport;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.cloudwatch.model.Dimension;
import software.amazon.awssdk.services.cloudwatch.model.Metric;
import software.amazon.awssdk.services.resourcegroupstaggingapi.model.Tag;

import java.util.Map;
import java.util.TreeMap;

import static ai.asserts.aws.resource.ResourceType.DynamoDBTable;
import static ai.asserts.aws.resource.ResourceType.LambdaFunction;
import static ai.asserts.aws.resource.ResourceType.S3Bucket;
import static ai.asserts.aws.resource.ResourceType.SQSQueue;
import static org.easymock.EasyMock.expect;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ResourceTest extends EasyMockSupport {
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

    @Test
    public void addLabels() {
        Resource resource = Resource.builder()
                .type(S3Bucket)
                .name("bucket")
                .build();

        Map<String, String> labels = new TreeMap<>();
        resource.addLabels(labels, "prefix");
        assertEquals(ImmutableMap.of("prefix_type", "S3Bucket", "prefix_name", "bucket"), labels);
    }

    @Test
    public void addTagLabels() {
        MetricNameUtil metricNameUtil = mock(MetricNameUtil.class);
        Resource resource = Resource.builder()
                .type(S3Bucket)
                .name("bucket")
                .tags(ImmutableList.of(Tag.builder()
                        .key("key1").value("value1")
                        .build(), Tag.builder()
                        .key("key2").value("value2")
                        .build()))
                .build();

        expect(metricNameUtil.toSnakeCase("key1")).andReturn("key_1");
        expect(metricNameUtil.toSnakeCase("key2")).andReturn("key_2");

        replayAll();

        Map<String, String> labels = new TreeMap<>();
        resource.addTagLabels(labels, metricNameUtil);
        assertEquals(ImmutableMap.of("tag_key_1", "value1", "tag_key_2", "value2"), labels);
        verifyAll();
    }
}
