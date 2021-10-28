/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.lambda;

import ai.asserts.aws.MetricNameUtil;
import com.google.common.collect.ImmutableMap;
import org.easymock.EasyMockSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.cloudwatch.model.Dimension;

import static org.easymock.EasyMock.expect;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class LambdaLabelConverterTest extends EasyMockSupport {
    private MetricNameUtil metricNameUtil;
    private LambdaLabelConverter testClass;

    @BeforeEach
    public void setup() {
        metricNameUtil = mock(MetricNameUtil.class);
        testClass = new LambdaLabelConverter(metricNameUtil);
    }

    @Test
    public void shouldUseForNamespace() {
        assertTrue(testClass.shouldUseForNamespace("AWS/Lambda"));
        assertTrue(testClass.shouldUseForNamespace("LambdaInsights"));
        assertFalse(testClass.shouldUseForNamespace("AWS/SQS"));
    }

    @Test
    public void convert() {
        expect(metricNameUtil.toSnakeCase("FunctionName")).andReturn("name");
        replayAll();
        assertEquals(ImmutableMap.of("d_name", "function1"), testClass.convert(Dimension.builder()
                .name("FunctionName")
                .value("function1")
                .build()));
        verifyAll();
    }

    @Test
    public void convert_resourceWithVersion() {
        replayAll();
        assertEquals(ImmutableMap.of("d_resource", "green", "d_executed_version", "1"),
                testClass.convert(Dimension.builder()
                        .name("Resource")
                        .value("green:1")
                        .build()));
        verifyAll();
    }

    @Test
    public void convert_resourceWithoutVersion() {
        replayAll();
        assertEquals(ImmutableMap.of("d_resource", "green"),
                testClass.convert(Dimension.builder()
                        .name("Resource")
                        .value("green")
                        .build()));
        verifyAll();
    }
}
