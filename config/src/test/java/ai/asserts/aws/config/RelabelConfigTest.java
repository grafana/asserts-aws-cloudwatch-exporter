/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.config;

import ai.asserts.aws.config.RelabelConfig;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class RelabelConfigTest {
    @Test
    public void buildReplacements() {
        RelabelConfig config = new RelabelConfig();
        config.setLabels(ImmutableList.of("__name__", "d_operation", "d_operation_type"));
        config.setRegex("aws_dynamodb_.+;(.+);(.+)");
        config.setTarget("asserts_request_context");
        config.setReplacement("$2-$1");
        config.validate();

        Map<String, String> labels = new TreeMap<>();
        labels.put("d_operation", "get");
        labels.put("d_operation_type", "read");

        Map<String, String> expected = new TreeMap<>(labels);
        expected.put("asserts_request_context", "read-get");
        assertEquals(expected, config.addReplacements("aws_dynamodb_successful_request_latency", labels));

        config.setLabels(ImmutableList.of("__name__", "some_label"));
        expected.remove("asserts_request_context");
        assertEquals(expected, config.addReplacements("aws_dynamodb_successful_request_latency", labels));
    }

    @Test
    public void buildReplacements_noMatch() {
        RelabelConfig config = new RelabelConfig();

        config.setLabels(ImmutableList.of("__name__", "some_label"));
        config.setRegex("aws_dynamodb_.+;(.+);(.+)");
        config.setTarget("asserts_request_context");
        config.setReplacement("$2-$1");
        config.validate();

        Map<String, String> labels = new TreeMap<>();
        labels.put("d_operation", "get");
        labels.put("d_operation_type", "read");

        Map<String, String> expected = new TreeMap<>(labels);
        assertEquals(expected, config.addReplacements("aws_dynamodb_successful_request_latency", labels));
    }

    @Test
    public void dropMetric() {
        RelabelConfig config = new RelabelConfig();
        config.setLabels(ImmutableList.of("__name__", "d_operation", "d_operation_type"));
        config.setRegex("aws_dynamodb_.+;(.+);(.+)");
        config.setTarget("asserts_request_context");
        config.setAction("drop-metric");
        config.validate();

        Map<String, String> labels = new TreeMap<>();
        labels.put("d_operation", "get");
        labels.put("d_operation_type", "read");

        assertTrue(config.dropMetric("aws_dynamodb_successful_request_latency", labels));

        config.setLabels(ImmutableList.of("__name__", "some_label"));
        assertFalse(config.dropMetric("aws_dynamodb_successful_request_latency", labels));
    }
}
