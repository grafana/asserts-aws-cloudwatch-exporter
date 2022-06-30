/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.exporter;

import com.google.common.annotations.VisibleForTesting;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.GetCallerIdentityResponse;

@Component
@Slf4j
public class AccountIDProvider implements InitializingBean {

    @Getter
    private String accountId = "unknown";

    @Override
    public void afterPropertiesSet() {
        try (StsClient stsClient = getStsClient()) {
            GetCallerIdentityResponse callerIdentity = stsClient.getCallerIdentity();
            accountId = callerIdentity.account();
        } catch (Exception e) {
            log.error("getCallerIdentity failed", e);
        }
    }

    @VisibleForTesting
    StsClient getStsClient() {
        return StsClient.builder().build();
    }
}
