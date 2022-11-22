/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws;

import ai.asserts.aws.AccountProvider.AWSAccount;
import ai.asserts.aws.config.ScrapeConfig;
import ai.asserts.aws.exporter.AccountIDProvider;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import org.easymock.EasyMockSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.easymock.EasyMock.expect;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class AccountProviderTest extends EasyMockSupport {
    private AccountIDProvider accountIDProvider;
    private ScrapeConfigProvider scrapeConfigProvider;
    private ScrapeConfig scrapeConfig;
    private AccountProvider testClass;

    @BeforeEach
    public void setup() {
        accountIDProvider = mock(AccountIDProvider.class);
        scrapeConfigProvider = mock(ScrapeConfigProvider.class);
        scrapeConfig = mock(ScrapeConfig.class);
        testClass = new AccountProvider(accountIDProvider, scrapeConfigProvider);
    }

    @Test
    public void getAccounts_ApiServerCredentialsMissing() {
        expect(scrapeConfigProvider.getScrapeConfig()).andReturn(scrapeConfig).anyTimes();
        expect(scrapeConfig.getRegions()).andReturn(ImmutableSet.of("region")).anyTimes();
        expect(scrapeConfig.getPauseAccounts()).andReturn(ImmutableSortedSet.of());
        expect(accountIDProvider.getAccountId()).andReturn("account1").anyTimes();
        replayAll();
        assertEquals(
                ImmutableSet.of(new AWSAccount("account1", null, null, null,
                        ImmutableSet.of("region"))),
                testClass.getAccounts()
        );
        verifyAll();
    }

    @Test
    public void getAccounts_ApiServerCredentialsPresent() {
        expect(scrapeConfig.getPauseAccounts()).andReturn(ImmutableSortedSet.of());
        expect(scrapeConfigProvider.getScrapeConfig()).andReturn(scrapeConfig).anyTimes();
        expect(scrapeConfig.getRegions()).andReturn(ImmutableSet.of("region")).anyTimes();
        expect(accountIDProvider.getAccountId()).andReturn("account1").anyTimes();

        replayAll();
        assertEquals(
                ImmutableSet.of(
                        new AWSAccount("account1", null, null, null, ImmutableSet.of("region"))
                ),
                testClass.getAccounts()
        );
        verifyAll();
    }

    @Test
    public void getAccounts_ApiServerCredentialsPresent_ProcessingPaused() {
        expect(accountIDProvider.getAccountId()).andReturn("account1").anyTimes();
        expect(scrapeConfig.getPauseAccounts()).andReturn(ImmutableSortedSet.of("account1"));
        expect(scrapeConfigProvider.getScrapeConfig()).andReturn(scrapeConfig).anyTimes();
        expect(scrapeConfig.getRegions()).andReturn(ImmutableSet.of("region")).anyTimes();

        replayAll();
        assertEquals(
                ImmutableSet.of(),
                testClass.getAccounts()
        );
        verifyAll();
    }

    @Test
    public void getCurrentAccountId() {
        expect(accountIDProvider.getAccountId()).andReturn("account-id").anyTimes();
        replayAll();
        assertEquals("account-id", testClass.getCurrentAccountId());
        verifyAll();
    }
}
