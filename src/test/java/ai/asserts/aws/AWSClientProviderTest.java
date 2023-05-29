/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws;

import ai.asserts.aws.account.AWSAccount;
import ai.asserts.aws.exporter.AccountIDProvider;
import org.easymock.Capture;
import org.easymock.EasyMockSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClientBuilder;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.StsClientBuilder;
import software.amazon.awssdk.services.sts.model.AssumeRoleRequest;
import software.amazon.awssdk.services.sts.model.AssumeRoleResponse;
import software.amazon.awssdk.services.sts.model.Credentials;

import java.time.Instant;

import static org.easymock.EasyMock.capture;
import static org.easymock.EasyMock.expect;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static software.amazon.awssdk.auth.credentials.AwsBasicCredentials.create;
import static software.amazon.awssdk.auth.credentials.AwsSessionCredentials.create;

public class AWSClientProviderTest extends EasyMockSupport {
    AccountIDProvider accountIDProvider;

    StsClient stsClient;
    StsClientBuilder stsClientBuilder;
    CloudWatchClient cloudWatchClient;
    CloudWatchClientBuilder cloudWatchClientBuilder;
    AWSClientProvider awsClientProvider;

    @BeforeEach
    void setup() {
        accountIDProvider = mock(AccountIDProvider.class);
        stsClient = mock(StsClient.class);
        stsClientBuilder = mock(StsClientBuilder.class);
        cloudWatchClient = mock(CloudWatchClient.class);
        cloudWatchClientBuilder = mock(CloudWatchClientBuilder.class);
        awsClientProvider = new AWSClientProvider(accountIDProvider) {
            @Override
            CloudWatchClientBuilder cloudWatchClientBuilder() {
                return cloudWatchClientBuilder;
            }

            @Override
            StsClientBuilder stsBuilder() {
                return stsClientBuilder;
            }
        };
    }

    @Test
    void getCloudWatchClient_withCredentialsAndRole() {
        Capture<AwsCredentialsProvider> assumeRoleCredentialsProviderCapture = Capture.newInstance();
        Capture<AwsCredentialsProvider> staticCredentialsProviderCapture = Capture.newInstance();
        Region us_west_2 = Region.of("us-west-2");
        expect(cloudWatchClientBuilder.region(us_west_2)).andReturn(cloudWatchClientBuilder);
        expect(cloudWatchClientBuilder.credentialsProvider(capture(assumeRoleCredentialsProviderCapture)))
                .andReturn(cloudWatchClientBuilder);
        expect(cloudWatchClientBuilder.build()).andReturn(cloudWatchClient);

        // Get Temp Credentials for Assume Role
        expect(stsClientBuilder.region(us_west_2)).andReturn(stsClientBuilder);
        expect(stsClientBuilder.credentialsProvider(capture(staticCredentialsProviderCapture)))
                .andReturn(stsClientBuilder);
        expect(stsClientBuilder.build()).andReturn(stsClient);
        expect(stsClient.assumeRole(AssumeRoleRequest.builder()
                .roleArn("role")
                .roleSessionName("session1")
                .build())).andReturn(AssumeRoleResponse.builder()
                .credentials(Credentials.builder()
                        .accessKeyId("tempAccessKeyId")
                        .secretAccessKey("tempSecretAccessKey")
                        .sessionToken("sessionToken")
                        .expiration(Instant.now().plusSeconds(3600))
                        .build())
                .build());
        stsClient.close();
        replayAll();

        // First time not cached
        AWSAccount account1 = AWSAccount.builder()
                .accountId("account-1")
                .accessId("accessId")
                .secretKey("secretKey")
                .assumeRole("role")
                .build();
        assertEquals(cloudWatchClient, awsClientProvider.getCloudWatchClient("us-west-2", account1));


        AwsSessionCredentials tempCredentials = create(
                "tempAccessKeyId", "tempSecretAccessKey", "sessionToken");
        AwsCredentialsProvider provider = assumeRoleCredentialsProviderCapture.getValue();
        assertNotNull(provider);
        assertEquals(tempCredentials, provider.resolveCredentials());

        AwsBasicCredentials basicCredentials = create("accessId", "secretKey");
        provider = staticCredentialsProviderCapture.getValue();
        assertEquals(basicCredentials, provider.resolveCredentials());
        verifyAll();

        // Next request is served from cache
        assertEquals(cloudWatchClient, awsClientProvider.getCloudWatchClient("us-west-2", account1));
    }
}
