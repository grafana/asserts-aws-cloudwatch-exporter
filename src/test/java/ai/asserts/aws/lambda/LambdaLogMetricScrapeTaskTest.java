/*
 * Copyright © 2021
 * Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.lambda;

import ai.asserts.aws.AWSClientProvider;
import ai.asserts.aws.cloudwatch.config.LogScrapeConfig;
import ai.asserts.aws.cloudwatch.config.NamespaceConfig;
import ai.asserts.aws.cloudwatch.config.ScrapeConfig;
import ai.asserts.aws.cloudwatch.config.ScrapeConfigProvider;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.prometheus.client.Collector;
import org.easymock.EasyMockSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;
import software.amazon.awssdk.services.cloudwatchlogs.model.FilteredLogEvent;

import java.util.Optional;
import java.util.regex.Pattern;

import static io.prometheus.client.Collector.Type.GAUGE;
import static org.easymock.EasyMock.expect;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class LambdaLogMetricScrapeTaskTest extends EasyMockSupport {
    private String region;
    private ScrapeConfigProvider scrapeConfigProvider;
    private ScrapeConfig scrapeConfig;
    private NamespaceConfig namespaceConfig;
    private CloudWatchLogsClient cloudWatchLogsClient;
    private LogScrapeConfig logScrapeConfig;
    private AWSClientProvider awsClientProvider;
    private LambdaFunctionScraper lambdaFunctionScraper;
    private LogEventScraper logEventScraper;
    private LogEventMetricEmitter logEventMetricEmitter;
    private Collector.MetricFamilySamples.Sample sample;
    private LambdaFunction lambdaFunction;
    private LambdaLogMetricScrapeTask testClass;

    @BeforeEach
    public void setup() {
        region = "region1";
        logScrapeConfig = mock(LogScrapeConfig.class);
        lambdaFunction = mock(LambdaFunction.class);

        scrapeConfigProvider = mock(ScrapeConfigProvider.class);
        awsClientProvider = mock(AWSClientProvider.class);
        cloudWatchLogsClient = mock(CloudWatchLogsClient.class);
        lambdaFunctionScraper = mock(LambdaFunctionScraper.class);
        logEventScraper = mock(LogEventScraper.class);
        sample = mock(Collector.MetricFamilySamples.Sample.class);
        logEventMetricEmitter = mock(LogEventMetricEmitter.class);

        scrapeConfig = mock(ScrapeConfig.class);
        namespaceConfig = mock(NamespaceConfig.class);


        testClass = new LambdaLogMetricScrapeTask(region);
        testClass.setLambdaFunctionScraper(lambdaFunctionScraper);
        testClass.setAwsClientProvider(awsClientProvider);
        testClass.setScrapeConfigProvider(scrapeConfigProvider);
        testClass.setLogEventScraper(logEventScraper);
        testClass.setLogEventMetricEmitter(logEventMetricEmitter);
    }

    @Test
    void scrape_whenLambdaEnabled() {
        expect(scrapeConfigProvider.getScrapeConfig()).andReturn(scrapeConfig).anyTimes();
        expect(scrapeConfig.getLambdaConfig()).andReturn(Optional.of(namespaceConfig)).anyTimes();
        expect(namespaceConfig.getLogs()).andReturn(ImmutableList.of(logScrapeConfig)).anyTimes();
        expect(lambdaFunctionScraper.getFunctions()).andReturn(ImmutableMap.of(
                region, ImmutableMap.of("arn1", lambdaFunction))
        ).anyTimes();
        expect(lambdaFunction.getName()).andReturn("fn1").anyTimes();
        expect(logScrapeConfig.shouldScrapeLogsFor("fn1")).andReturn(true);
        expect(awsClientProvider.getCloudWatchLogsClient(region)).andReturn(cloudWatchLogsClient);
        FilteredLogEvent filteredLogEvent = FilteredLogEvent.builder()
                .message("message")
                .build();
        expect(logEventScraper.findLogEvent(cloudWatchLogsClient, lambdaFunction, logScrapeConfig))
                .andReturn(Optional.of(filteredLogEvent));
        expect(logEventMetricEmitter.getSample(namespaceConfig, LambdaLogMetricScrapeTask.FunctionLogScrapeConfig.builder()
                .lambdaFunction(lambdaFunction)
                .logScrapeConfig(logScrapeConfig)
                .build(), filteredLogEvent)).andReturn(Optional.of(sample));
        cloudWatchLogsClient.close();
        replayAll();
        testClass.update();
        assertEquals(ImmutableList.of(
                new Collector.MetricFamilySamples("aws_lambda_logs", GAUGE, "", ImmutableList.of(sample))
        ), testClass.collect());
        verifyAll();
    }

    @Test
    void regexp() {
        String test = "2021-10-04T07:07:11.556Z\t4abd5c76-8f49-5f1e-b218-05a22502b456\tINFO\tv2 About to to put " +
                "message in SQS Queue https://sqs.us-west-2.amazonaws.com/342994379019/lamda-sqs-poc-output-queue";
        Pattern compile = Pattern.compile(".*v2 About to to put message in SQS " +
                "Queue https://sqs.us-west-2.amazonaws.com/342994379019/(.+)");
        assertTrue(compile.matcher(test).matches());
    }
}
