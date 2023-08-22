/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.account;

import ai.asserts.aws.AssertsServerUtil;
import ai.asserts.aws.EnvironmentConfig;
import ai.asserts.aws.ScrapeConfigProvider;
import ai.asserts.aws.config.ScrapeConfig;
import ai.asserts.aws.exporter.AccountIDProvider;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Suppliers;
import com.google.common.collect.Sets;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;

import static java.util.concurrent.TimeUnit.MINUTES;
import static org.springframework.util.CollectionUtils.isEmpty;

@Slf4j
@AllArgsConstructor
public class SingleInstanceAccountProvider implements AccountProvider {
    public static final String TSDB_USER_NAME = "remoteWrite_basicAuth_username";
    private final EnvironmentConfig environmentConfig;
    private final AccountIDProvider accountIDProvider;
    private final ScrapeConfigProvider scrapeConfigProvider;
    private final RestTemplate restTemplate;
    private final AssertsServerUtil assertsServerUtil;
    private final Supplier<Set<AWSAccount>> accountsCache =
            Suppliers.memoizeWithExpiration(this::getAccountsInternal, 5, MINUTES);

    @Override
    public Set<AWSAccount> getAccounts() {
        return accountsCache.get();
    }

    private Set<AWSAccount> getAccountsInternal() {
        if (environmentConfig.isDisabled()) {
            return Collections.emptySet();
        }
        String tenantName = getTenantName();
        ScrapeConfig scrapeConfig = scrapeConfigProvider.getScrapeConfig(tenantName);
        Map<String, AWSAccount> accountRegions = new HashMap<>();
        Set<String> regions = scrapeConfig.getRegions();
        if (regions.isEmpty()) {
            regions = new TreeSet<>();
            regions.add("us-west-2");
        }

        // Get Configured AWS Accounts
        if (scrapeConfig.isFetchAccountConfigs()) {
            String cloudwatchConfigUrl = assertsServerUtil.getAssertsTenantBaseUrl() +
                    "/api-server/v1/config/cloudwatch";
            ResponseEntity<CloudwatchConfigs> response = restTemplate.exchange(cloudwatchConfigUrl,
                    HttpMethod.GET,
                    assertsServerUtil.createAssertsAuthHeader(),
                    new ParameterizedTypeReference<CloudwatchConfigs>() {
                    });
            if (response.getStatusCode().is2xxSuccessful()) {
                Set<String> finalRegions = regions;
                if (response.getBody() != null && !isEmpty(response.getBody().getCloudWatchConfigs())) {
                    response.getBody().getCloudWatchConfigs()
                            .stream()
                            .filter(awsAccount -> {
                                if (awsAccount.isPaused()) {
                                    log.info("Account {} is paused", awsAccount.getAccountId());
                                }
                                return !awsAccount.isPaused();
                            })
                            .forEach(awsAccount -> {
                                awsAccount.getRegions().addAll(finalRegions);
                                awsAccount.setTenant(tenantName);
                                accountRegions.putIfAbsent(awsAccount.getAccountId(), awsAccount);
                            });
                }
            }
        }
        if (scrapeConfig.isScrapeCurrentAccount()) {
            String accountId = accountIDProvider.getAccountId();
            AWSAccount ac = new AWSAccount(tenantName, accountId, null, null, null,
                    regions);
            accountRegions.putIfAbsent(ac.getAccountId(), ac);
        }
        log.info("Scraping AWS Accounts {}", accountRegions);
        return Sets.newHashSet(accountRegions.values());
    }

    @VisibleForTesting
    String getTenantName() {
        return System.getenv(TSDB_USER_NAME);
    }

    @EqualsAndHashCode
    @Getter
    @SuperBuilder
    @ToString
    @NoArgsConstructor
    public static class CloudwatchConfigs {
        List<AWSAccount> cloudWatchConfigs;
    }
}
