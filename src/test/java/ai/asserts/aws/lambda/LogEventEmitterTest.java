/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.lambda;

import ai.asserts.aws.AccountProvider.AWSAccount;
import ai.asserts.aws.MetricNameUtil;
import ai.asserts.aws.config.LogScrapeConfig;
import ai.asserts.aws.config.NamespaceConfig;
import ai.asserts.aws.exporter.LambdaLogMetricScrapeTask;
import ai.asserts.aws.exporter.MetricSampleBuilder;
import ai.asserts.aws.resource.Resource;
import ai.asserts.aws.resource.ResourceTagHelper;
import com.google.common.collect.ImmutableSet;
import io.prometheus.client.Collector;
import org.easymock.EasyMockSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.cloudwatchlogs.model.FilteredLogEvent;

import java.util.Map;
import java.util.Optional;

import static ai.asserts.aws.MetricNameUtil.SCRAPE_ACCOUNT_ID_LABEL;
import static org.easymock.EasyMock.expect;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class LogEventEmitterTest extends EasyMockSupport {
    private AWSAccount account;
    private ResourceTagHelper resourceTagHelper;
    private Resource resource;
    private MetricNameUtil metricNameUtil;
    private MetricSampleBuilder sampleBuilder;
    private Collector.MetricFamilySamples.Sample sample;
    private NamespaceConfig namespaceConfig;
    private LambdaFunction lambdaFunction;
    private LogScrapeConfig logScrapeConfig;
    private Map<String, String> labels;
    private LogEventMetricEmitter testClass;

    @BeforeEach
    public void setup() {
        account = mock(AWSAccount.class);
        resourceTagHelper = mock(ResourceTagHelper.class);
        resource = mock(Resource.class);
        metricNameUtil = mock(MetricNameUtil.class);
        sampleBuilder = mock(MetricSampleBuilder.class);
        sample = mock(Collector.MetricFamilySamples.Sample.class);
        labels = mock(Map.class);
        namespaceConfig = mock(NamespaceConfig.class);
        logScrapeConfig = mock(LogScrapeConfig.class);
        lambdaFunction = mock(LambdaFunction.class);

        testClass = new LogEventMetricEmitter(resourceTagHelper, metricNameUtil, sampleBuilder);
    }

    @Test
    public void emitMetric_withResourceTags() {
        expect(resourceTagHelper.getFilteredResources(account, "region1", namespaceConfig))
                .andReturn(ImmutableSet.of(resource));
        expect(logScrapeConfig.extractLabels("message")).andReturn(labels);
        expect(lambdaFunction.getRegion()).andReturn("region1").anyTimes();
        expect(lambdaFunction.getName()).andReturn("fn1").anyTimes();
        expect(lambdaFunction.getAccount()).andReturn(SCRAPE_ACCOUNT_ID_LABEL);
        expect(labels.size()).andReturn(1);
        expect(labels.put("region", "region1")).andReturn(null);
        expect(labels.put("d_function_name", "fn1")).andReturn(null);
        expect(labels.put(SCRAPE_ACCOUNT_ID_LABEL, SCRAPE_ACCOUNT_ID_LABEL)).andReturn(null);
        expect(resource.getArn()).andReturn("arn1");
        expect(lambdaFunction.getArn()).andReturn("arn1");
        resource.addEnvLabel(labels, metricNameUtil);

        expect(sampleBuilder.buildSingleSample("aws_lambda_logs", labels, 1.0D))
                .andReturn(sample);

        replayAll();
        assertEquals(Optional.of(sample), testClass.getSample(namespaceConfig,
                LambdaLogMetricScrapeTask.FunctionLogScrapeConfig.builder()
                        .account(account)
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
        expect(resourceTagHelper.getFilteredResources(account, "region1", namespaceConfig))
                .andReturn(ImmutableSet.of());
        expect(logScrapeConfig.extractLabels("message")).andReturn(labels);
        expect(lambdaFunction.getRegion()).andReturn("region1").anyTimes();
        expect(lambdaFunction.getName()).andReturn("fn1").anyTimes();
        expect(lambdaFunction.getAccount()).andReturn(SCRAPE_ACCOUNT_ID_LABEL).anyTimes();
        expect(labels.size()).andReturn(1);
        expect(labels.put("region", "region1")).andReturn(null);
        expect(labels.put(SCRAPE_ACCOUNT_ID_LABEL, SCRAPE_ACCOUNT_ID_LABEL)).andReturn(null);
        expect(labels.put("d_function_name", "fn1")).andReturn(null);
        expect(sampleBuilder.buildSingleSample("aws_lambda_logs", labels, 1.0D))
                .andReturn(sample);

        replayAll();
        assertEquals(Optional.of(sample), testClass.getSample(namespaceConfig,
                LambdaLogMetricScrapeTask.FunctionLogScrapeConfig.builder()
                        .account(account)
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
