/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.lambda;

import ai.asserts.aws.MetricNameUtil;
import ai.asserts.aws.cloudwatch.prometheus.GaugeExporter;
import ai.asserts.aws.resource.Resource;
import ai.asserts.aws.resource.ResourceMapper;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;
import org.easymock.EasyMockSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.DestinationConfig;
import software.amazon.awssdk.services.lambda.model.FunctionConfiguration;
import software.amazon.awssdk.services.lambda.model.FunctionEventInvokeConfig;
import software.amazon.awssdk.services.lambda.model.ListFunctionEventInvokeConfigsRequest;
import software.amazon.awssdk.services.lambda.model.ListFunctionEventInvokeConfigsResponse;
import software.amazon.awssdk.services.lambda.model.OnFailure;
import software.amazon.awssdk.services.lambda.model.OnSuccess;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.easymock.EasyMock.expect;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class LambdaFunctionBuilderTest extends EasyMockSupport {
    private MetricNameUtil metricNameUtil;
    private GaugeExporter gaugeExporter;
    private LambdaClient lambdaClient;
    private Resource destResource;
    private Resource fnResource;
    private ResourceMapper resourceMapper;
    private LambdaFunctionBuilder testClass;
    private Instant now;

    @BeforeEach
    public void setup() {
        metricNameUtil = mock(MetricNameUtil.class);
        gaugeExporter = mock(GaugeExporter.class);
        destResource = mock(Resource.class);
        fnResource = mock(Resource.class);
        resourceMapper = mock(ResourceMapper.class);
        lambdaClient = mock(LambdaClient.class);
        now = Instant.now();
        testClass = new LambdaFunctionBuilder(metricNameUtil, gaugeExporter, resourceMapper) {
            @Override
            Instant now() {
                return now;
            }
        };
    }

    @Test
    public void buildFunction() {
        ListFunctionEventInvokeConfigsRequest request = ListFunctionEventInvokeConfigsRequest.builder()
                .functionName("fn1")
                .build();

        ListFunctionEventInvokeConfigsResponse response = ListFunctionEventInvokeConfigsResponse.builder()
                .functionEventInvokeConfigs(FunctionEventInvokeConfig.builder()
                        .functionArn("fn1:arn")
                        .destinationConfig(DestinationConfig.builder()
                                .onSuccess(OnSuccess.builder()
                                        .destination("dst1:arn")
                                        .build())
                                .build())
                        .build(), FunctionEventInvokeConfig.builder()
                        .functionArn("fn1:arn")
                        .destinationConfig(DestinationConfig.builder()
                                .onFailure(OnFailure.builder()
                                        .destination("dst2:arn")
                                        .build())
                                .build())
                        .build())
                .build();

        expect(lambdaClient.listFunctionEventInvokeConfigs(request)).andReturn(response);
        expect(metricNameUtil.getMetricPrefix("AWS/Lambda")).andReturn("prefix");

        Map<String, String> baseLabels = ImmutableMap.of("function_name", "fn1", "region", "region1");

        expect(resourceMapper.map("dst1:arn")).andReturn(Optional.of(destResource));
        Map<String, String> successLabels = ImmutableSortedMap.of(
                "on", "success", "function_name", "fn1", "region", "region1");
        fnResource.addTagLabels(baseLabels, metricNameUtil);
        destResource.addLabels(successLabels, "dest");
        gaugeExporter.exportMetric("prefix_invoke_config", "", successLabels, now, 1.0D);

        expect(resourceMapper.map("dst2:arn")).andReturn(Optional.of(destResource));
        Map<String, String> failureLabels = ImmutableSortedMap.of(
                "on", "failure", "function_name", "fn1", "region", "region1");
        fnResource.addTagLabels(baseLabels, metricNameUtil);
        destResource.addLabels(failureLabels, "dest");
        gaugeExporter.exportMetric("prefix_invoke_config", "", failureLabels, now, 1.0D);

        replayAll();

        assertEquals(
                LambdaFunction.builder()
                        .name("fn1")
                        .arn("fn1:arn")
                        .region("region1")
                        .resource(fnResource)
                        .build()
                ,
                testClass.buildFunction("region1", lambdaClient,
                        FunctionConfiguration.builder()
                                .functionName("fn1")
                                .functionArn("fn1:arn")
                                .build(), Optional.of(fnResource)));
        verifyAll();
    }
}
