/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws;

import ai.asserts.aws.config.ScrapeConfig;
import ai.asserts.aws.exporter.AccountIDProvider;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Suppliers;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.SuperBuilder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;

import static java.util.concurrent.TimeUnit.MINUTES;
import static org.springframework.util.CollectionUtils.isEmpty;

@Component
@Slf4j
@AllArgsConstructor
public class AccountProvider {
    private final AccountIDProvider accountIDProvider;
    private final ScrapeConfigProvider scrapeConfigProvider;
    private final RestTemplate restTemplate;
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
        Set<String> regions = scrapeConfig.getRegions();
        if (regions.isEmpty()) {
            regions = new TreeSet<>();
            regions.add("us-west-2");
        }
        if (scrapeConfig.isScrapeCurrentAccount()) {
            String accountId = accountIDProvider.getAccountId();
            AWSAccount ac = new AWSAccount(accountId, null, null, null, regions);
            accountRegions.add(ac);
            log.info("Scraping AWS Accounts {}", accountRegions);
        }

        // Get Configured AWS Accounts
        String cloudwatchConfigUrl = scrapeConfigProvider.getAssertsTenantBaseUrl() +
                "/api-server/v1/config/cloudwatch";
        ResponseEntity<CloudwatchConfigs> response = restTemplate.exchange(cloudwatchConfigUrl,
                HttpMethod.GET,
                scrapeConfigProvider.createAssertsAuthHeader(),
                new ParameterizedTypeReference<CloudwatchConfigs>() {
                });
        if (response.getStatusCode().is2xxSuccessful()) {
            Set<String> finalRegions = regions;
            if (response.getBody() != null && !isEmpty(response.getBody().getCloudWatchConfigs())) {
                response.getBody().getCloudWatchConfigs()
                        .stream()
                        .filter(awsAccount -> {
                            if (awsAccount.paused) {
                                log.info("Account {} is paused", awsAccount.accountId);
                            }
                            return !awsAccount.paused;
                        })
                        .forEach(awsAccount -> {
                            awsAccount.getRegions().addAll(finalRegions);
                            accountRegions.add(awsAccount);
                        });
            }

        }
        return accountRegions;
    }

    @EqualsAndHashCode
    @Getter
    @SuperBuilder
    @ToString
    public static class CloudwatchConfigs {
        List<AWSAccount> cloudWatchConfigs;
    }

    @AllArgsConstructor
    @Getter
    @EqualsAndHashCode
    @ToString
    @SuperBuilder
    public static class AWSAccount {
        // Use different json field name to match property from API Response
        @JsonProperty("accountID")
        private final String accountId;
        private final String name;
        @ToString.Exclude
        private final String accessId;
        @ToString.Exclude
        private final String secretKey;
        // Use different json field name to match property from API Response
        @JsonProperty("assumeRoleARN")
        private final String assumeRole;
        private final String externalId;
        private final boolean paused;
        @Builder.Default
        private final Set<String> regions = new TreeSet<>();

        public AWSAccount(String accountId, String accessId, String secretKey, String assumeRole, Set<String> regions) {
            this(accountId, null, accessId, secretKey, assumeRole, null, false, regions);
        }
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
