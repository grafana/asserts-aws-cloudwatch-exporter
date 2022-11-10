/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws;

import ai.asserts.aws.config.ScrapeConfig;
import ai.asserts.aws.exporter.AccountIDProvider;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.google.common.base.Suppliers;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.SuperBuilder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;

import static java.util.concurrent.TimeUnit.MINUTES;

@Component
@Slf4j
@AllArgsConstructor
public class AccountProvider {
    private final AccountIDProvider accountIDProvider;
    private final ScrapeConfigProvider scrapeConfigProvider;
    private final Supplier<Set<AWSAccount>> accountsCache =
            Suppliers.memoizeWithExpiration(this::getAccountsInternal, 5, MINUTES);

    public String getCurrentAccountId() {
        return accountIDProvider.getAccountId();
    }

    public Set<AWSAccount> getAccounts() {
        return accountsCache.get();
    }

    private Set<AWSAccount> getAccountsInternal() {
        ScrapeConfig scrapeConfig = scrapeConfigProvider.getScrapeConfig();
        Set<AWSAccount> accountRegions = new LinkedHashSet<>();
        if (!scrapeConfig.isPauseAllProcessing()) {
            String accountId = accountIDProvider.getAccountId();
            Set<String> regions = scrapeConfig.getRegions();
            if (regions.isEmpty()) {
                regions = new TreeSet<>();
                regions.add("us-west-2");
            }
            accountRegions.add(new AWSAccount(accountId, null, null, null, regions));
            log.info("Scraping AWS Accounts {}", accountRegions);
        }
        return accountRegions;
    }

    @AllArgsConstructor
    @Getter
    @EqualsAndHashCode
    @ToString
    @SuperBuilder
    public static class AWSAccount {
        private final String accountId;
        @ToString.Exclude
        private final String accessId;
        @ToString.Exclude
        private final String secretKey;
        private final String assumeRole;
        private final Set<String> regions;
    }

    @Getter
    @Setter
    @ToString
    @JsonIgnoreProperties(ignoreUnknown = true)
    @EqualsAndHashCode
    @AllArgsConstructor
    @NoArgsConstructor
    public static class AccountConfig {
        private String accountID;
        @ToString.Exclude
        private String accessKey;
        @ToString.Exclude
        private String secretKey;
        private String assumeRoleARN;
    }

    @Getter
    @Setter
    @JsonIgnoreProperties(ignoreUnknown = true)
    @AllArgsConstructor
    @NoArgsConstructor
    public static class ResponseDto {
        private List<AccountConfig> cloudWatchConfigs;
    }
}
