/*
 * Copyright © 2021
 * Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.lambda;

import ai.asserts.aws.AWSClientProvider;
import ai.asserts.aws.MetricNameUtil;
import ai.asserts.aws.cloudwatch.config.LogScrapeConfig;
import ai.asserts.aws.cloudwatch.config.NamespaceConfig;
import ai.asserts.aws.cloudwatch.config.ScrapeConfig;
import ai.asserts.aws.cloudwatch.config.ScrapeConfigProvider;
import ai.asserts.aws.cloudwatch.prometheus.GaugeExporter;
import ai.asserts.aws.resource.Resource;
import ai.asserts.aws.resource.TagFilterResourceProvider;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import org.easymock.EasyMockSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;
import software.amazon.awssdk.services.cloudwatchlogs.model.FilterLogEventsRequest;
import software.amazon.awssdk.services.cloudwatchlogs.model.FilterLogEventsResponse;
import software.amazon.awssdk.services.cloudwatchlogs.model.FilteredLogEvent;

import java.time.Instant;
import java.util.Optional;
import java.util.regex.Pattern;

import static org.easymock.EasyMock.anyObject;
import static org.easymock.EasyMock.expect;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class LambdaLogMetricScrapeTaskTest extends EasyMockSupport {
    private String region;
    private ScrapeConfigProvider scrapeConfigProvider;
    private ScrapeConfig scrapeConfig;
    private NamespaceConfig namespaceConfig;
    private TagFilterResourceProvider tagFilterResourceProvider;
    private MetricNameUtil metricNameUtil;
    private Resource resource;
    private CloudWatchLogsClient cloudWatchLogsClient;
    private LogScrapeConfig logScrapeConfig;
    private GaugeExporter gaugeExporter;
    private Instant now;
    private AWSClientProvider awsClientProvider;
    private LambdaFunctionScraper lambdaFunctionScraper;
    private LambdaLogMetricScrapeTask testClass;

    @BeforeEach
    public void setup() {
        now = Instant.now();
        region = "region1";
        logScrapeConfig = LogScrapeConfig.builder()
                .lambdaFunctionName("function.+")
                .logFilterPattern("published OrderRequest to")
                .regexPattern(".+?v2 About to to put message in SQS Queue https://sqs.us-west-2.amazonaws.com/342994379019/(.+)")
                .labels(ImmutableMap.of("message_type", "SQSQueue", "sqs_queue_name", "$1"))
                .build();
        logScrapeConfig.compile();

        scrapeConfigProvider = mock(ScrapeConfigProvider.class);
        awsClientProvider = mock(AWSClientProvider.class);
        cloudWatchLogsClient = mock(CloudWatchLogsClient.class);
        lambdaFunctionScraper = mock(LambdaFunctionScraper.class);
        gaugeExporter = mock(GaugeExporter.class);
        tagFilterResourceProvider = mock(TagFilterResourceProvider.class);
        metricNameUtil = mock(MetricNameUtil.class);
        scrapeConfig = mock(ScrapeConfig.class);
        namespaceConfig = mock(NamespaceConfig.class);
        resource = mock(Resource.class);
        testClass = new LambdaLogMetricScrapeTask(region, ImmutableList.of(logScrapeConfig)) {
            @Override
            Instant now() {
                return now;
            }
        };
        testClass.setLambdaFunctionScraper(lambdaFunctionScraper);
        testClass.setAwsClientProvider(awsClientProvider);
        testClass.setGaugeExporter(gaugeExporter);
        testClass.setScrapeConfigProvider(scrapeConfigProvider);
        testClass.setTagFilterResourceProvider(tagFilterResourceProvider);
        testClass.setMetricNameUtil(metricNameUtil);
    }

    @Test
    void scrape_whenLambdaEnabled() {
        expect(scrapeConfigProvider.getScrapeConfig()).andReturn(scrapeConfig).anyTimes();
        expect(scrapeConfig.getLambdaConfig()).andReturn(Optional.of(namespaceConfig)).anyTimes();
        expect(tagFilterResourceProvider.getFilteredResources(region, namespaceConfig))
                .andReturn(ImmutableSet.of(resource));

        expect(resource.getArn()).andReturn("arn1").anyTimes();

        expect(lambdaFunctionScraper.getFunctions()).andReturn(ImmutableMap.of(
                region, ImmutableMap.of(
                        "arn1", LambdaFunction.builder().name("function-1").arn("arn1").build(),
                        "arn2", LambdaFunction.builder().name("Function-2").arn("arn2").build()))
        ).anyTimes();

        expect(awsClientProvider.getCloudWatchLogsClient(region)).andReturn(cloudWatchLogsClient);
        FilterLogEventsRequest request = FilterLogEventsRequest.builder()
                .limit(10)
                .endTime(now.minusSeconds(60).toEpochMilli())
                .startTime(now.minusSeconds(120).toEpochMilli())
                .logGroupName("/aws/lambda/function-1")
                .filterPattern("published OrderRequest to")
                .build();

        FilterLogEventsResponse response = FilterLogEventsResponse.builder()
                .events(ImmutableList.of(
                        FilteredLogEvent.builder()
                                .message("2021-10-04T06:44:08.523Z\td54eff3e-3322-5e89-aa5b-a6ea97fd54fb\tINFO\tv2 About to to put message in SQS Queue https://sqs.us-west-2.amazonaws.com/342994379019/lamda-sqs-poc-output-queue")
                                .build()
                ))
                .build();

        expect(cloudWatchLogsClient.filterLogEvents(request)).andReturn(response);
        expect(metricNameUtil.getResourceTagLabels(resource)).andReturn(ImmutableMap.of("tag", "value"));
        gaugeExporter.exportMetric(anyObject(), anyObject(), anyObject(), anyObject(), anyObject());

        gaugeExporter.exportMetric("aws_lambda_logs", "",
                ImmutableSortedMap.of(
                        "region", region,
                        "d_function_name", "function-1",
                        "d_message_type", "SQSQueue",
                        "tag", "value",
                        "d_sqs_queue_name", "lamda-sqs-poc-output-queue"),
                now.minusSeconds(60), 1.0D);

        replayAll();
        testClass.run();
        verifyAll();
    }

    @Test
    void scrape_noFunctions() {
        expect(scrapeConfigProvider.getScrapeConfig()).andReturn(scrapeConfig).anyTimes();
        expect(scrapeConfig.getLambdaConfig()).andReturn(Optional.of(namespaceConfig)).anyTimes();
        expect(lambdaFunctionScraper.getFunctions()).andReturn(ImmutableMap.of()).anyTimes();

        replayAll();
        testClass.run();
        verifyAll();
    }

    @Test
    void scrape_Exception() {
        expect(scrapeConfigProvider.getScrapeConfig()).andReturn(scrapeConfig).anyTimes();
        expect(scrapeConfig.getLambdaConfig()).andReturn(Optional.of(namespaceConfig)).anyTimes();
        expect(lambdaFunctionScraper.getFunctions()).andReturn(ImmutableMap.of(
                region, ImmutableMap.of(
                        "arn1", LambdaFunction.builder().name("function-1").build()))
        ).anyTimes();

        expect(tagFilterResourceProvider.getFilteredResources(region, namespaceConfig))
                .andThrow(new RuntimeException());

        expect(awsClientProvider.getCloudWatchLogsClient(region)).andReturn(cloudWatchLogsClient);

        replayAll();
        testClass.run();
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
