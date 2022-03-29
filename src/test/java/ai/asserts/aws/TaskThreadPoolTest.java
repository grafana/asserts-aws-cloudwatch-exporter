/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws;

import ai.asserts.aws.cloudwatch.config.ScrapeConfig;
import ai.asserts.aws.cloudwatch.config.ScrapeConfigProvider;
import io.micrometer.core.instrument.MeterRegistry;
import org.easymock.EasyMockSupport;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;

import static org.easymock.EasyMock.expect;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class TaskThreadPoolTest extends EasyMockSupport {
    @Test
    public void constructor() {
        ScrapeConfigProvider scrapeConfigProvider = mock(ScrapeConfigProvider.class);
        ScrapeConfig scrapeConfig = mock(ScrapeConfig.class);
        MeterRegistry mockMeterRegistry = mock(MeterRegistry.class);
        ExecutorService mockService = mock(ExecutorService.class);

        expect(scrapeConfigProvider.getScrapeConfig()).andReturn(scrapeConfig);
        expect(scrapeConfig.getNumTaskThreads()).andReturn(2);

        replayAll();
        new TaskThreadPool(scrapeConfigProvider, mockMeterRegistry) {
            @Override
            ExecutorService executorService(MeterRegistry meterRegistry, Integer numTaskThreads) {
                assertEquals(mockMeterRegistry, meterRegistry);
                assertEquals(2, numTaskThreads);
                return mockService;
            }
        };
        verifyAll();
    }
}
