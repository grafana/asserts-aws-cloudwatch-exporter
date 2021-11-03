/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.lambda;

import ai.asserts.aws.MetricNameUtil;
import ai.asserts.aws.cloudwatch.config.LogScrapeConfig;
import ai.asserts.aws.cloudwatch.config.NamespaceConfig;
import ai.asserts.aws.cloudwatch.metrics.MetricSampleBuilder;
import ai.asserts.aws.resource.Resource;
import ai.asserts.aws.resource.TagFilterResourceProvider;
import com.google.common.collect.ImmutableSet;
import io.prometheus.client.Collector;
import org.easymock.EasyMockSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.cloudwatchlogs.model.FilteredLogEvent;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.easymock.EasyMock.expect;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class LogEventEmitterTest extends EasyMockSupport {
    private TagFilterResourceProvider tagFilterResourceProvider;
    private Resource resource;
    private MetricNameUtil metricNameUtil;
    private MetricSampleBuilder sampleBuilder;
    private Collector.MetricFamilySamples.Sample sample;
    private Instant now;
    private NamespaceConfig namespaceConfig;
    private LambdaFunction lambdaFunction;
    private LogScrapeConfig logScrapeConfig;
    private Map<String, String> labels;
    private LogEventMetricEmitter testClass;

    @BeforeEach
    public void setup() {
        tagFilterResourceProvider = mock(TagFilterResourceProvider.class);
        resource = mock(Resource.class);
        metricNameUtil = mock(MetricNameUtil.class);
        sampleBuilder = mock(MetricSampleBuilder.class);
        sample = mock(Collector.MetricFamilySamples.Sample.class);
        labels = mock(Map.class);
        namespaceConfig = mock(NamespaceConfig.class);
        logScrapeConfig = mock(LogScrapeConfig.class);
        lambdaFunction = mock(LambdaFunction.class);

        now = Instant.now();
        testClass = new LogEventMetricEmitter(tagFilterResourceProvider, metricNameUtil, sampleBuilder) {
            @Override
            Instant getNow() {
                return now;
            }
        };
    }

    @Test
    public void emitMetric_withResourceTags() {
        expect(tagFilterResourceProvider.getFilteredResources("region1", namespaceConfig))
                .andReturn(ImmutableSet.of(resource));
        expect(logScrapeConfig.extractLabels("message")).andReturn(labels);
        expect(lambdaFunction.getRegion()).andReturn("region1").anyTimes();
        expect(lambdaFunction.getName()).andReturn("fn1").anyTimes();
        expect(labels.size()).andReturn(1);
        expect(labels.put("region", "region1")).andReturn(null);
        expect(labels.put("d_function_name", "fn1")).andReturn(null);
        expect(resource.getArn()).andReturn("arn1");
        expect(lambdaFunction.getArn()).andReturn("arn1");
        resource.addTagLabels(labels, metricNameUtil);

        expect(sampleBuilder.buildSingleSample("aws_lambda_logs", labels, now, 1.0D))
                .andReturn(sample);

        replayAll();
        assertEquals(Optional.of(sample), testClass.getSample(namespaceConfig,
                LambdaLogMetricScrapeTask.FunctionLogScrapeConfig.builder()
                        .logScrapeConfig(logScrapeConfig)
                        .lambdaFunction(lambdaFunction)
                        .build(),
                FilteredLogEvent.builder()
                        .message("message")
                        .build()
        ));
        verifyAll();
    }

    @Test
    public void emitMetric_withoutResource() {
        expect(tagFilterResourceProvider.getFilteredResources("region1", namespaceConfig))
                .andReturn(ImmutableSet.of());
        expect(logScrapeConfig.extractLabels("message")).andReturn(labels);
        expect(lambdaFunction.getRegion()).andReturn("region1").anyTimes();
        expect(lambdaFunction.getName()).andReturn("fn1").anyTimes();
        expect(labels.size()).andReturn(1);
        expect(labels.put("region", "region1")).andReturn(null);
        expect(labels.put("d_function_name", "fn1")).andReturn(null);
        expect(sampleBuilder.buildSingleSample("aws_lambda_logs", labels, now, 1.0D))
                .andReturn(sample);

        replayAll();
        assertEquals(Optional.of(sample), testClass.getSample(namespaceConfig,
                LambdaLogMetricScrapeTask.FunctionLogScrapeConfig.builder()
                        .logScrapeConfig(logScrapeConfig)
                        .lambdaFunction(lambdaFunction)
                        .build(),
                FilteredLogEvent.builder()
                        .message("message")
                        .build()
        ));
        verifyAll();
    }
}
