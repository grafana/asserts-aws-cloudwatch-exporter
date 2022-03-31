/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.cloudwatch.config;

import io.micrometer.core.instrument.util.StringUtils;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import static ai.asserts.aws.cloudwatch.config.AuthConfig.AuthType.ApiTokenInConfig;
import static ai.asserts.aws.cloudwatch.config.AuthConfig.AuthType.ApiTokenInSecretsManager;
import static ai.asserts.aws.cloudwatch.config.AuthConfig.AuthType.NoAuth;

@Getter
@Setter
@EqualsAndHashCode
@ToString
@SuperBuilder
@NoArgsConstructor
public class AuthConfig {
    @Builder.Default
    private AuthType type = NoAuth;
    private String apiToken;
    private String secretARN;

    public enum AuthType {
        NoAuth, ApiTokenInConfig, ApiTokenInSecretsManager
    }

    public void validate() {
        switch (type) {
            case NoAuth:
                break;
            case ApiTokenInConfig:
                if (StringUtils.isEmpty(apiToken)) {
                    throw new RuntimeException("Authentication type set to " + ApiTokenInConfig
                            + " but api token is not specified");
                }
                break;
            case ApiTokenInSecretsManager:
                if (StringUtils.isEmpty(secretARN)) {
                    throw new RuntimeException("Authentication type set to " + ApiTokenInSecretsManager
                            + " but secret ARN is not specified");
                }
                break;
        }
    }

    public boolean isAuthenticationRequired() {
        return !NoAuth.equals(type);
    }
}
