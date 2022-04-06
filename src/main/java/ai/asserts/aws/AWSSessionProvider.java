/*
 *  Copyright © 2022.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws;

import ai.asserts.aws.cloudwatch.TimeWindowBuilder;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.AssumeRoleRequest;
import software.amazon.awssdk.services.sts.model.AssumeRoleResponse;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

@Component
public class AWSSessionProvider {
    private final TimeWindowBuilder timeWindowBuilder;
    private final AtomicReference<Map<String, AWSSessionConfig>> currentSession;

    public AWSSessionProvider(TimeWindowBuilder timeWindowBuilder) {
        this.timeWindowBuilder = timeWindowBuilder;
        currentSession = new AtomicReference<>();
        currentSession.set(new HashMap<>());
    }

    public Optional<AWSSessionConfig> getSessionCredential(String region, String assumeRole) {
        if (assumeRole != null) {
            if (currentSession.get().get(assumeRole) != null &&
                    timeWindowBuilder.getZonedDateTime(region).toInstant().
                            compareTo(currentSession.get().get(assumeRole).getExpiring()) < 0) {
                return Optional.of(currentSession.get().get(assumeRole));
            }
            StsClient stsClient = StsClient.builder().region(Region.of(region))
                    .credentialsProvider(StaticCredentialsProvider
                            .create(AwsBasicCredentials.create("AKIAU7XAO4EFTCSBSJKT", "iRMBUlkw1YQxxsFW+8onon+F/qtNITw9zGeJFHDe")))
                    .build();
            AssumeRoleResponse response = stsClient.assumeRole(AssumeRoleRequest.builder()
                    .roleSessionName("session1")
                    .roleArn(assumeRole)
                    .build());
            if (response.credentials() != null) {
                Map<String, AWSSessionConfig> preValue = currentSession.get();
                AWSSessionConfig newConfig = AWSSessionConfig.builder()
                        .accessKeyId(response.credentials().accessKeyId())
                        .secretAccessKey(response.credentials().secretAccessKey())
                        .sessionToken(response.credentials().sessionToken())
                        .expiring(response.credentials().expiration())
                        .build();
                Map<String, AWSSessionConfig> newValue = new HashMap<>(preValue);
                newValue.put(assumeRole, newConfig);
                currentSession.compareAndSet(preValue, newValue);
                return Optional.of(currentSession.get().get(assumeRole));
            }
        }
        return Optional.empty();
    }
}
