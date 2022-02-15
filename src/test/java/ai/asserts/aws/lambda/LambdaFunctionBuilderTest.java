/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.lambda;

import ai.asserts.aws.resource.Resource;
import ai.asserts.aws.resource.ResourceMapper;
import org.easymock.EasyMockSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.lambda.model.FunctionConfiguration;

import java.util.Optional;

import static org.easymock.EasyMock.expect;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class LambdaFunctionBuilderTest extends EasyMockSupport {
    private Resource fnResource;
    private ResourceMapper resourceMapper;
    private LambdaFunctionBuilder testClass;

    @BeforeEach
    public void setup() {
        fnResource = mock(Resource.class);
        resourceMapper = mock(ResourceMapper.class);
        testClass = new LambdaFunctionBuilder(resourceMapper);
    }

    @Test
    public void buildFunction() {
        expect(resourceMapper.map("fn1:arn")).andReturn(Optional.of(fnResource));
        expect(fnResource.getAccount()).andReturn("account");
        replayAll();

        assertEquals(
                LambdaFunction.builder()
                        .name("fn1")
                        .arn("fn1:arn")
                        .region("region1")
                        .account("account")
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
