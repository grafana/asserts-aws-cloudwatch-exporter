/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.lambda;

import ai.asserts.aws.resource.Resource;
import org.easymock.EasyMockSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.lambda.model.FunctionConfiguration;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class LambdaFunctionBuilderTest extends EasyMockSupport {
    private Resource fnResource;
    private LambdaFunctionBuilder testClass;

    @BeforeEach
    public void setup() {
        fnResource = mock(Resource.class);
        testClass = new LambdaFunctionBuilder();
    }

    @Test
    public void buildFunction() {

        replayAll();

        assertEquals(
                LambdaFunction.builder()
                        .name("fn1")
                        .arn("fn1:arn")
                        .region("region1")
                        .resource(fnResource)
                        .timeoutSeconds(60)
                        .memoryMB(128)
                        .build()
                ,
                testClass.buildFunction("region1",
                        FunctionConfiguration.builder()
                                .functionName("fn1")
                                .functionArn("fn1:arn")
                                .timeout(60)
                                .memorySize(128)
                                .build(), Optional.of(fnResource)));
        verifyAll();
    }
}
