/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws;

import com.google.common.annotations.VisibleForTesting;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Map;

import static ai.asserts.aws.ApiServerConstants.ASSERTS_API_SERVER_URL;
import static ai.asserts.aws.ApiServerConstants.ASSERTS_TENANT_HEADER;

@Component
@Slf4j
public class AssertsServerUtil {
    public String getAssertsTenantBaseUrl() {
        return getEnv().get(ASSERTS_API_SERVER_URL);
    }

    public String getExporterConfigUrl() {
        return getAssertsTenantBaseUrl() + "/api-server/v1/config/aws-exporter";
    }

    public String getAlertForwardUrl() {
        return getAssertsTenantBaseUrl() + "/assertion-detector/external-alerts/prometheus";
    }

    public HttpEntity<String> createAssertsAuthHeader() {
        Map<String, String> envVariables = getEnv();
        String username = envVariables.get(ApiServerConstants.ASSERTS_USER);
        log.info("Using credentials of user {}", username);
        String password = envVariables.get(ApiServerConstants.ASSERTS_PASSWORD);
        if (StringUtils.hasText(username) && StringUtils.hasText(password)) {
            HttpHeaders headers = new HttpHeaders();
            headers.setBasicAuth(username, password);
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.add(ASSERTS_TENANT_HEADER, username);
            return new HttpEntity<>(headers);
        } else {
            return null;
        }
    }

    @VisibleForTesting
    Map<String, String> getEnv() {
        return System.getenv();
    }
}
