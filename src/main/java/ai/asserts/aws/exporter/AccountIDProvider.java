/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.exporter;

import ai.asserts.aws.AWSClientProvider;
import ai.asserts.aws.ScrapeConfigProvider;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sts.StsClient;

@Component
@Slf4j
public class AccountIDProvider implements InitializingBean {
    private final AWSClientProvider awsClientProvider;
    private final ScrapeConfigProvider scrapeConfigProvider;

    @Getter
    private String accountId;

    public AccountIDProvider(AWSClientProvider awsClientProvider, ScrapeConfigProvider scrapeConfigProvider) {
        this.awsClientProvider = awsClientProvider;
        this.scrapeConfigProvider = scrapeConfigProvider;
    }

    @Override
    public void afterPropertiesSet() {
        if (!scrapeConfigProvider.getScrapeConfig().getRegions().isEmpty()) {
            String anyRegion = scrapeConfigProvider.getScrapeConfig().getRegions().iterator().next();
            StsClient stsClient = awsClientProvider.getStsClient(anyRegion);
            accountId = stsClient.getCallerIdentity().account();
        }
    }
}
