/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws;

import ai.asserts.aws.AccountProvider.AWSAccount;
import ai.asserts.aws.AccountProvider.AccountConfig;
import ai.asserts.aws.config.ScrapeConfig;
import ai.asserts.aws.exporter.AccountIDProvider;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import org.easymock.Capture;
import org.easymock.EasyMockSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.RequestEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static ai.asserts.aws.ApiServerConstants.ASSERTS_HOST;
import static ai.asserts.aws.ApiServerConstants.ASSERTS_PASSWORD;
import static ai.asserts.aws.ApiServerConstants.ASSERTS_USER;
import static org.easymock.EasyMock.capture;
import static org.easymock.EasyMock.eq;
import static org.easymock.EasyMock.expect;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.MediaType.APPLICATION_JSON;

public class AccountProviderTest extends EasyMockSupport {
    private RestTemplate restTemplate;
    private AccountIDProvider accountIDProvider;
    private ScrapeConfigProvider scrapeConfigProvider;
    private ScrapeConfig scrapeConfig;
    private AccountProvider testClass;
    private HttpHeaders mockHeaders;
    private String basicAuthUser;
    private String basicAuthPassword;
    private Map<String, String> envProperties;

    @BeforeEach
    public void setup() {
        envProperties = new TreeMap<>();
        restTemplate = mock(RestTemplate.class);
        accountIDProvider = mock(AccountIDProvider.class);
        scrapeConfigProvider = mock(ScrapeConfigProvider.class);
        mockHeaders = new HttpHeaders() {
            @Override
            public void setBasicAuth(String username, String password) {
                basicAuthUser = username;
                basicAuthPassword = password;
                super.setBasicAuth(username, password);
            }
        };
        scrapeConfig = mock(ScrapeConfig.class);
        testClass = new AccountProvider(accountIDProvider, scrapeConfigProvider, restTemplate) {
            @Override
            Map<String, String> getEnvVariables() {
                return envProperties;
            }

            @Override
            HttpHeaders newHttpHeaders() {
                return mockHeaders;
            }
        };
    }

    @Test
    public void getAccounts_ApiServerCredentialsMissing() {
        expect(scrapeConfigProvider.getScrapeConfig()).andReturn(scrapeConfig);
        expect(scrapeConfig.getRegions()).andReturn(ImmutableSet.of("region"));
        expect(accountIDProvider.getAccountId()).andReturn("account1");
        replayAll();
        assertEquals(
                ImmutableSet.of(new AWSAccount("account1", null, ImmutableSet.of("region"))),
                testClass.getAccounts()
        );
        verifyAll();
    }

    @Test
    public void getAccounts_ApiServerCredentialsPresent() {
        envProperties.put(ASSERTS_HOST, "host");
        envProperties.put(ASSERTS_USER, "user");
        envProperties.put(ASSERTS_PASSWORD, "password");
        HttpHeaders headers = new HttpHeaders();
        headers.setBasicAuth("user", "password");
        headers.setAccept(ImmutableList.of(APPLICATION_JSON));
        HttpEntity<Map<String, String>> entity = new HttpEntity<>(headers);
        expect(scrapeConfigProvider.getScrapeConfig()).andReturn(scrapeConfig);
        expect(scrapeConfig.getRegions()).andReturn(ImmutableSet.of("region")).anyTimes();
        expect(accountIDProvider.getAccountId()).andReturn("account1");

        Capture<RequestEntity> requestEntityCapture = Capture.newInstance();

        mockHeaders.setBasicAuth("user", "password");
        mockHeaders.setAccept(ImmutableList.of(APPLICATION_JSON));

        expect(restTemplate.exchange(
                eq("host/api-server/v1/config/cloudwatch"),
                eq(GET),
                capture(requestEntityCapture),
                eq(new ParameterizedTypeReference<List<AccountConfig>>() {
                })))
                .andReturn(ResponseEntity.ok(
                        ImmutableList.of(new AccountConfig("account2", "role2"))));

        replayAll();
        assertEquals(
                ImmutableSet.of(
                        new AWSAccount("account1", null, ImmutableSet.of("region")),
                        new AWSAccount("account2", "role2", ImmutableSet.of("region"))
                ),
                testClass.getAccounts()
        );
        assertAll(
                () -> assertNotNull(requestEntityCapture.getValue()),
                () -> assertEquals("user", basicAuthUser),
                () -> assertEquals("password", basicAuthPassword)
        );
        verifyAll();
    }
}
