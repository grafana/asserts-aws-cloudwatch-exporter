/*
 *  Copyright © 2022.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws;

import ai.asserts.aws.AccountProvider.AWSAccount;
import ai.asserts.aws.cloudwatch.TimeWindowBuilder;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.StsClientBuilder;
import software.amazon.awssdk.services.sts.model.AssumeRoleRequest;
import software.amazon.awssdk.services.sts.model.AssumeRoleResponse;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.springframework.util.StringUtils.hasLength;
import static software.amazon.awssdk.auth.credentials.AwsBasicCredentials.create;

@Component
public class AWSSessionProvider {
    private final TimeWindowBuilder timeWindowBuilder;
    private final AtomicReference<AWSSessionConfig> currentSession;

    public AWSSessionProvider(TimeWindowBuilder timeWindowBuilder) {
        this.timeWindowBuilder = timeWindowBuilder;
        currentSession = new AtomicReference<>();
        currentSession.set(null);
    }

    public Optional<AWSSessionConfig> getSessionCredential(String region, AWSAccount account) {
        if (account.getAssumeRole() != null) {
            if (currentSession.get() != null &&
                    timeWindowBuilder.getZonedDateTime(region).toInstant().
                            compareTo(currentSession.get().getExpiring()) < 0) {
                return Optional.of(currentSession.get());
            }
            StsClientBuilder stsClientBuilder = StsClient.builder();
            if (hasLength(account.getAccountId()) && hasLength(account.getSecretKey())) {
                stsClientBuilder = stsClientBuilder
                        .credentialsProvider(() -> create(account.getAccessId(), account.getSecretKey()));
            }
            StsClient stsClient = stsClientBuilder.region(Region.of(region)).build();
            AssumeRoleResponse response = stsClient.assumeRole(AssumeRoleRequest.builder()
                    .roleSessionName("session1")
                    .roleArn(account.getAssumeRole())
                    .build());
            if (response.credentials() != null) {
                AWSSessionConfig preValue = currentSession.get();
                AWSSessionConfig newValue = AWSSessionConfig.builder()
                        .accessKeyId(response.credentials().accessKeyId())
                        .secretAccessKey(response.credentials().secretAccessKey())
                        .sessionToken(response.credentials().sessionToken())
                        .expiring(response.credentials().expiration())
                        .build();
                currentSession.compareAndSet(preValue, newValue);
                return Optional.of(currentSession.get());
            }
        }
        return Optional.empty();
    }
}
