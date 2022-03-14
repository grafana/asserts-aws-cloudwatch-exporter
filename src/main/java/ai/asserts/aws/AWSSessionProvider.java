/*
 *  Copyright © 2022.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws;

import ai.asserts.aws.cloudwatch.TimeWindowBuilder;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.AssumeRoleRequest;
import software.amazon.awssdk.services.sts.model.AssumeRoleResponse;

import java.time.Instant;
import java.util.Optional;

@Component
public class AWSSessionProvider {
    private final TimeWindowBuilder timeWindowBuilder;
    private AWSSessionConfig currentSession;

    public AWSSessionProvider(TimeWindowBuilder timeWindowBuilder) {
        this.timeWindowBuilder = timeWindowBuilder;
        currentSession = null;
    }

    public Optional<AWSSessionConfig> getSessionCredential(String region, String assumeRole) {
        if (assumeRole != null) {
            if (currentSession != null &&
                    timeWindowBuilder.getZonedDateTime(region).toInstant().compareTo(currentSession.getExpiring()) < 0) {
                return Optional.of(currentSession);
            }
            StsClient stsClient = StsClient.builder().region(Region.of(region))
                    .build();
            AssumeRoleResponse response = stsClient.assumeRole(AssumeRoleRequest.builder()
                    .roleSessionName("session1")
                    .roleArn(assumeRole)
                    .build());
            if (response.credentials() != null) {
                currentSession = AWSSessionConfig.builder()
                        .accessKeyId(response.credentials().accessKeyId())
                        .secretAccessKey(response.credentials().secretAccessKey())
                        .sessionToken(response.credentials().sessionToken())
                        .expiring(response.credentials().expiration())
                        .build();
                return Optional.of(currentSession);
            }
        }
        return Optional.empty();
    }
}
