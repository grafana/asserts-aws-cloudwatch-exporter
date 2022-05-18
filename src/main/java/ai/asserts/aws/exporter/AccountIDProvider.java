/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.exporter;

import ai.asserts.aws.AWSClientProvider;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.GetCallerIdentityResponse;

@Component
@Slf4j
public class AccountIDProvider implements InitializingBean {
    private final AWSClientProvider awsClientProvider;

    @Getter
    private String accountId = "unknown";

    public AccountIDProvider(AWSClientProvider awsClientProvider) {
        this.awsClientProvider = awsClientProvider;
    }

    @Override
    public void afterPropertiesSet() {
        try (StsClient stsClient = awsClientProvider.getStsClient("us-west-2")) {
            GetCallerIdentityResponse callerIdentity = stsClient.getCallerIdentity();
            accountId = callerIdentity.account();
        } catch (Exception e) {
            log.error("getCallerIdentity failed", e);
        }
    }
}
