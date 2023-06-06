/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws;

import io.micrometer.core.instrument.MeterRegistry;
import org.easymock.EasyMockSupport;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class TaskThreadPoolTest extends EasyMockSupport {
    @Test
    public void constructor() {
        MeterRegistry mockMeterRegistry = mock(MeterRegistry.class);
        ExecutorService mockService = mock(ExecutorService.class);
        replayAll();
        new TaskThreadPool("test pool", 1, mockMeterRegistry) {
            @Override
            ExecutorService buildExecutorService(String name, int nThreads, MeterRegistry meterRegistry) {
                assertEquals("test pool", name);
                assertEquals(1, nThreads);
                return mockService;
            }
        };
        verifyAll();
    }
}
