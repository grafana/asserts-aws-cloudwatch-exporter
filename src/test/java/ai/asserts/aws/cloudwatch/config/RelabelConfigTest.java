/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.cloudwatch.config;

import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class RelabelConfigTest {
    @Test
    public void buildReplacements() {
        RelabelConfig config = new RelabelConfig();

//        - source_labels: [__name__, d_operation, d_operation_type]
//          regex: aws_dynamodb_.+;(.+);(.+)
//          target_label: asserts_request_context
//          replacement: $1-$2
        config.setLabels(ImmutableList.of("__name__", "d_operation", "d_operation_type"));
        config.setRegex("aws_dynamodb_.+;(.+);(.+)");
        config.setTarget("asserts_request_context");
        config.setReplacement("$2-$1");
        config.compile();

        Map<String, String> labels = new TreeMap<>();
        labels.put("d_operation", "get");
        labels.put("d_operation_type", "read");

        Map<String, String> expected = new TreeMap<>(labels);
        expected.put("asserts_request_context", "read-get");
        assertEquals(expected, config.addReplacements("aws_dynamodb_successful_request_latency", labels));
    }
}
