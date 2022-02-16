/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.exporter;

import ai.asserts.aws.AWSClientProvider;
import ai.asserts.aws.cloudwatch.config.ScrapeConfig;
import ai.asserts.aws.cloudwatch.config.ScrapeConfigProvider;
import com.google.common.collect.ImmutableSet;
import org.easymock.EasyMockSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.GetCallerIdentityResponse;

import static org.easymock.EasyMock.expect;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class AccountIDProviderTest extends EasyMockSupport {
    private AWSClientProvider awsClientProvider;
    private StsClient stsClient;
    private ScrapeConfigProvider scrapeConfigProvider;
    private ScrapeConfig scrapeConfig;
    private AccountIDProvider testClass;

    @BeforeEach
    public void setup() {
        awsClientProvider = mock(AWSClientProvider.class);
        stsClient = mock(StsClient.class);
        scrapeConfig = mock(ScrapeConfig.class);
        scrapeConfigProvider = mock(ScrapeConfigProvider.class);
        testClass = new AccountIDProvider(awsClientProvider, scrapeConfigProvider);
    }

    @Test
    public void afterPropertiesSet() {
        expect(scrapeConfigProvider.getScrapeConfig()).andReturn(scrapeConfig);
        expect(scrapeConfig.getRegions()).andReturn(ImmutableSet.of("region"));
        expect(awsClientProvider.getStsClient("region")).andReturn(stsClient);
        expect(stsClient.getCallerIdentity()).andReturn(GetCallerIdentityResponse.builder()
                .account("TestAccount")
                .build());
        replayAll();
        testClass.afterPropertiesSet();
        assertEquals("TestAccount", testClass.getAccountId());
        verifyAll();
    }
}
