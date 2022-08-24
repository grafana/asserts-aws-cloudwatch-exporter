/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.config;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.SuperBuilder;
import org.springframework.util.StringUtils;

import static ai.asserts.aws.config.AuthConfig.AuthType.ApiTokenInConfig;
import static ai.asserts.aws.config.AuthConfig.AuthType.ApiTokenInSecretsManager;
import static ai.asserts.aws.config.AuthConfig.AuthType.NoAuth;

@Getter
@Setter
@EqualsAndHashCode
@ToString
@SuperBuilder
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class AuthConfig {
    @Builder.Default
    private AuthType type = NoAuth;
    private String apiToken;
    private String secretARN;
    private String assertsUser;
    private String assertsPassword;

    public enum AuthType {
        NoAuth, ApiTokenInConfig, ApiTokenInSecretsManager, ApiTokenInAsserts
    }

    public void validate() {
        switch (type) {
            case NoAuth:
                break;
            case ApiTokenInConfig:
                if (!StringUtils.hasLength(apiToken)) {
                    throw new RuntimeException("Authentication type set to " + ApiTokenInConfig
                            + " but api token is not specified");
                }
                break;
            case ApiTokenInSecretsManager:
                if (!StringUtils.hasLength(secretARN)) {
                    throw new RuntimeException("Authentication type set to " + ApiTokenInSecretsManager
                            + " but secret ARN is not specified");
                }
                break;
        }
    }

    @JsonIgnore
    public boolean isAuthenticationRequired() {
        return !NoAuth.equals(type);
    }
}
