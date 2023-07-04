/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.exporter;

import ai.asserts.aws.EnvironmentConfig;
import org.easymock.EasyMockSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.GetCallerIdentityResponse;

import static org.easymock.EasyMock.expect;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class AccountIDProviderTest extends EasyMockSupport {
    private EnvironmentConfig environmentConfig;
    private StsClient stsClient;
    private AccountIDProvider testClass;

    @BeforeEach
    public void setup() {
        environmentConfig = new EnvironmentConfig("true", "single", "single-tenant-single-instance");
        stsClient = mock(StsClient.class);
        testClass = new AccountIDProvider(environmentConfig) {
            @Override
            StsClient getStsClient() {
                return stsClient;
            }
        };
    }

    @Test
    public void afterPropertiesSet() {
        expect(stsClient.getCallerIdentity()).andReturn(GetCallerIdentityResponse.builder()
                .account("TestAccount")
                .build());
        stsClient.close();
        replayAll();
        testClass.afterPropertiesSet();
        assertEquals("TestAccount", testClass.getAccountId());
        verifyAll();
    }
}
