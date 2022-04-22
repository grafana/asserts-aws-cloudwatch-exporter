/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws;

import ai.asserts.aws.config.ScrapeConfig;
import ai.asserts.aws.exporter.AccountIDProvider;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Set;

@Component
@Slf4j
@AllArgsConstructor
public class AccountRegionProvider {
    private final AccountIDProvider accountIDProvider;
    private final ScrapeConfigProvider scrapeConfigProvider;

    public Set<AccountRegion> getAccountAndRegions() {
        Set<AccountRegion> accountRegions = new LinkedHashSet<>();
        ScrapeConfig scrapeConfig = scrapeConfigProvider.getScrapeConfig();
        accountRegions.add(new AccountRegion(accountIDProvider.getAccountId(), null, scrapeConfig.getRegions()));
        return accountRegions;
    }

    @AllArgsConstructor
    @Getter
    @EqualsAndHashCode
    public static class AccountRegion {
        private final String account;
        private final String assumeRole;
        private final Set<String> regions;
    }
}
