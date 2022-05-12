/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws;

import ai.asserts.aws.config.ScrapeConfig;
import ai.asserts.aws.exporter.AccountIDProvider;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static ai.asserts.aws.ApiServerConstants.ASSERTS_API_SERVER_URL;
import static ai.asserts.aws.ApiServerConstants.ASSERTS_PASSWORD;
import static ai.asserts.aws.ApiServerConstants.ASSERTS_USER;
import static java.util.concurrent.TimeUnit.MINUTES;
import static org.springframework.http.MediaType.APPLICATION_JSON;

@Component
@Slf4j
@AllArgsConstructor
public class AccountProvider {
    private final AccountIDProvider accountIDProvider;
    private final ScrapeConfigProvider scrapeConfigProvider;
    private final RestTemplate restTemplate;
    private final Supplier<Set<AWSAccount>> accountsCache =
            Suppliers.memoizeWithExpiration(this::getAccountsInternal, 5, MINUTES);

    public Set<AWSAccount> getAccounts() {
        return accountsCache.get();
    }

    private Set<AWSAccount> getAccountsInternal() {
        ScrapeConfig scrapeConfig = scrapeConfigProvider.getScrapeConfig();
        Set<AWSAccount> accountRegions = new LinkedHashSet<>(getAccountsFromApiServer(scrapeConfig));
        String accountId = accountIDProvider.getAccountId();
        if (accountRegions.stream().noneMatch(account -> account.getAccountId().equals(accountId))) {
            accountRegions.add(new AWSAccount(accountId, null, scrapeConfig.getRegions()));
        }
        return accountRegions;
    }

    @VisibleForTesting
    Map<String, String> getEnvVariables() {
        return System.getenv();
    }

    private Set<AWSAccount> getAccountsFromApiServer(ScrapeConfig scrapeConfig) {
        Map<String, String> envVariables = getEnvVariables();
        Set<AWSAccount> awsAccounts = new LinkedHashSet<>();
        if (Stream.of(ASSERTS_API_SERVER_URL, ASSERTS_PASSWORD, ASSERTS_USER).allMatch(envVariables::containsKey)) {
            String user = envVariables.get(ASSERTS_USER);
            String password = envVariables.get(ASSERTS_PASSWORD);
            String url = getAccountApiUrl(envVariables);
            log.info("Will load configuration from server [{}] with credentials of user [{}]", url, user);
            try {
                ResponseEntity<ResponseDto> response = restTemplate.exchange(url,
                        HttpMethod.GET,
                        createAuthHeader(user, password),
                        new ParameterizedTypeReference<ResponseDto>() {
                        });
                if (response.getStatusCode().is2xxSuccessful()) {
                    ResponseDto responseDto = response.getBody();
                    if (responseDto != null && responseDto.getCloudwatchConfigs() != null) {
                        log.info("API Server returned AWS Accounts {}", responseDto.getCloudwatchConfigs());
                        awsAccounts.addAll(responseDto.getCloudwatchConfigs().stream()
                                .map(config -> new AWSAccount(config.accountID, config.assumeRoleARN, scrapeConfig.getRegions()))
                                .collect(Collectors.toSet()));
                    }
                }
            } catch (RestClientException e) {
                log.error("Call to ApiServer to fetch accounts failed", e);
            }
        }
        return awsAccounts;
    }

    @AllArgsConstructor
    @Getter
    @EqualsAndHashCode
    @ToString
    public static class AWSAccount {
        private final String accountId;
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
        private String assumeRoleARN;
    }

    @Getter
    @Setter
    @JsonIgnoreProperties(ignoreUnknown = true)
    @AllArgsConstructor
    @NoArgsConstructor
    public static class ResponseDto {
        private List<AccountConfig> cloudwatchConfigs;
    }


    private HttpEntity<?> createAuthHeader(String username, String password) {
        if (StringUtils.hasText(username) && StringUtils.hasText(password)) {
            HttpHeaders headers = newHttpHeaders();
            headers.setBasicAuth(username, password);
            headers.setAccept(ImmutableList.of(APPLICATION_JSON));
            return new HttpEntity<>(headers);
        } else {
            return null;
        }
    }

    @VisibleForTesting
    HttpHeaders newHttpHeaders() {
        return new HttpHeaders();
    }

    @VisibleForTesting
    String getAccountApiUrl(Map<String, String> envVariables) {
        return envVariables.get(ASSERTS_API_SERVER_URL) + "/api-server/v1/config/cloudwatch";
    }
}
