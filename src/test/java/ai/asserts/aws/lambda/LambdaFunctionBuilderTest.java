/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.lambda;

import ai.asserts.aws.TaskExecutorUtil;
import ai.asserts.aws.account.AWSAccount;
import ai.asserts.aws.resource.Resource;
import ai.asserts.aws.resource.ResourceMapper;
import org.easymock.EasyMockSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.lambda.model.FunctionConfiguration;

import java.util.Optional;

import static ai.asserts.aws.MetricNameUtil.SCRAPE_ACCOUNT_ID_LABEL;
import static org.easymock.EasyMock.expect;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class LambdaFunctionBuilderTest extends EasyMockSupport {
    private Resource fnResource;
    private ResourceMapper resourceMapper;
    private TaskExecutorUtil taskExecutorUtil;
    private LambdaFunctionBuilder testClass;

    @BeforeEach
    public void setup() {
        fnResource = mock(Resource.class);
        resourceMapper = mock(ResourceMapper.class);
        taskExecutorUtil = mock(TaskExecutorUtil.class);
        testClass = new LambdaFunctionBuilder(resourceMapper, taskExecutorUtil);
    }

    @Test
    public void buildFunction() {
        expect(resourceMapper.map("fn1:arn")).andReturn(Optional.of(fnResource));
        expect(fnResource.getAccount()).andReturn(SCRAPE_ACCOUNT_ID_LABEL);
        expect(taskExecutorUtil.getAccountDetails()).andReturn(AWSAccount.builder()
                        .tenant("acme")
                        .accountId(SCRAPE_ACCOUNT_ID_LABEL)
                .build());
        replayAll();

        assertEquals(
                LambdaFunction.builder()
                        .tenant("acme")
                        .name("fn1")
                        .arn("fn1:arn")
                        .region("region1")
                        .account(SCRAPE_ACCOUNT_ID_LABEL)
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
