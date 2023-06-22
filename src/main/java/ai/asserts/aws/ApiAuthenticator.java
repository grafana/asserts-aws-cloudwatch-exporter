/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws;

import ai.asserts.aws.config.AuthConfig;
import ai.asserts.aws.config.ScrapeConfig;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse;

import java.util.Optional;

import static ai.asserts.aws.config.AuthConfig.AuthType.ApiTokenInConfig;
import static java.util.concurrent.TimeUnit.MINUTES;

@Slf4j
@Component
public class ApiAuthenticator {
    private final ScrapeConfigProvider scrapeConfigProvider;
    private final AWSClientProvider awsClientProvider;
    private final LoadingCache<String, String> secretCache;

    public ApiAuthenticator(ScrapeConfigProvider scrapeConfigProvider, AWSClientProvider awsClientProvider) {
        this.scrapeConfigProvider = scrapeConfigProvider;
        this.awsClientProvider = awsClientProvider;
        secretCache = CacheBuilder.newBuilder()
                .expireAfterWrite(10, MINUTES)
                .build(new CacheLoader<String, String>() {
                    @Override
                    public String load(@NonNull String key) {
                        return getSecret(key);
                    }
                });
    }

    @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
    public void authenticate(Optional<String> apiTokenOpt) {
        ScrapeConfig scrapeConfig = scrapeConfigProvider.getScrapeConfig(null);
        AuthConfig authConfig = scrapeConfig.getAuthConfig();
        if (apiTokenOpt.isPresent()) {
            if (authConfig.isAuthenticationRequired() && !getExpectedToken(authConfig).equals(apiTokenOpt.get())) {
                throw new RuntimeException("Authentication failed");
            }
        } else if (authConfig.isAuthenticationRequired()) {
            throw new RuntimeException("Authentication token not provided");
        }
    }

    private String getExpectedToken(AuthConfig authConfig) {
        if (authConfig.getType().equals(ApiTokenInConfig)) {
            return authConfig.getApiToken();
        } else {
            return secretCache.getUnchecked(authConfig.getSecretARN());
        }
    }

    private String getSecret(String secretARN) {
        GetSecretValueResponse secretValue = awsClientProvider.getSecretsManagerClient(region())
                .getSecretValue(GetSecretValueRequest.builder()
                        .secretId(secretARN)
                        .build());
        return secretValue.secretString();
    }

    private String region() {
        return scrapeConfigProvider.getScrapeConfig(null).getRegions().iterator().next();
    }
}
