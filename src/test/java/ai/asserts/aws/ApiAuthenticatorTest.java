/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws;

import ai.asserts.aws.config.AuthConfig;
import ai.asserts.aws.config.ScrapeConfig;
import com.google.common.collect.ImmutableSet;
import org.easymock.EasyMockSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse;

import java.util.Optional;

import static ai.asserts.aws.config.AuthConfig.AuthType.ApiTokenInConfig;
import static ai.asserts.aws.config.AuthConfig.AuthType.ApiTokenInSecretsManager;
import static ai.asserts.aws.config.AuthConfig.AuthType.NoAuth;
import static org.easymock.EasyMock.expect;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class ApiAuthenticatorTest extends EasyMockSupport {
    private ScrapeConfig scrapeConfig;
    private AWSClientProvider awsClientProvider;
    private SecretsManagerClient secretsManagerClient;
    private ApiAuthenticator testClass;

    @BeforeEach
    public void setup() {
        ScrapeConfigProvider scrapeConfigProvider = mock(ScrapeConfigProvider.class);
        awsClientProvider = mock(AWSClientProvider.class);
        scrapeConfig = mock(ScrapeConfig.class);
        secretsManagerClient = mock(SecretsManagerClient.class);
        testClass = new ApiAuthenticator(scrapeConfigProvider, awsClientProvider);
        expect(scrapeConfigProvider.getScrapeConfig(null)).andReturn(scrapeConfig).anyTimes();
    }

    @Test
    public void noAuth() {
        AuthConfig authConfig = AuthConfig.builder()
                .type(NoAuth)
                .build();
        expect(scrapeConfig.getAuthConfig()).andReturn(authConfig).anyTimes();
        replayAll();
        testClass.authenticate(Optional.empty());
        testClass.authenticate(Optional.of("token"));
        verifyAll();
    }

    @Test
    public void authTokenInConfig() {
        AuthConfig authConfig = AuthConfig.builder()
                .type(ApiTokenInConfig)
                .apiToken("token")
                .build();
        expect(scrapeConfig.getAuthConfig()).andReturn(authConfig).anyTimes();

        replayAll();
        testClass.authenticate(Optional.of("token"));
        assertThrows(RuntimeException.class, () -> testClass.authenticate(Optional.of("token1")));
        assertThrows(RuntimeException.class, () -> testClass.authenticate(Optional.empty()));
        verifyAll();
    }

    @Test
    public void authTokenInSecretsManager() {
        AuthConfig authConfig = AuthConfig.builder()
                .type(ApiTokenInSecretsManager)
                .secretARN("secretARN")
                .build();
        expect(scrapeConfig.getAuthConfig()).andReturn(authConfig).anyTimes();
        expect(scrapeConfig.getRegions()).andReturn(ImmutableSet.of("region")).anyTimes();
        expect(awsClientProvider.getSecretsManagerClient("region")).andReturn(secretsManagerClient).anyTimes();
        expect(secretsManagerClient.getSecretValue(GetSecretValueRequest.builder()
                .secretId("secretARN")
                .build())).andReturn(GetSecretValueResponse.builder()
                .secretString("token")
                .build()).anyTimes();

        replayAll();

        testClass.authenticate(Optional.of("token"));
        assertThrows(RuntimeException.class, () -> testClass.authenticate(Optional.of("token1")));
        assertThrows(RuntimeException.class, () -> testClass.authenticate(Optional.empty()));

        verifyAll();
    }
}
