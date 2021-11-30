/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.lambda;

import ai.asserts.aws.resource.Resource;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.lambda.model.FunctionConfiguration;

import java.util.Optional;

/**
 * Builds a lambda function given the function configuration and function resource and emits metrics for each
 * invoke config on the function or any of its aliases or versions
 */
@Component
@AllArgsConstructor
@Slf4j
public class LambdaFunctionBuilder {

    @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
    public LambdaFunction buildFunction(String region, FunctionConfiguration functionConfiguration,
                                        Optional<Resource> fnResource) {

        return LambdaFunction.builder()
                .region(region)
                .name(functionConfiguration.functionName())
                .arn(functionConfiguration.functionArn())
                .resource(fnResource.orElse(null))
                .memoryMB(functionConfiguration.memorySize())
                .timeoutSeconds(functionConfiguration.timeout())
                .build();
    }
}
