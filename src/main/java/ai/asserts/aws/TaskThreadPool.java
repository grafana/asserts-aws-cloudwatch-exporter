/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws;

import com.google.common.annotations.VisibleForTesting;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.jvm.ExecutorServiceMetrics;
import io.micrometer.core.instrument.util.NamedThreadFactory;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Getter
@Slf4j
public class TaskThreadPool {
    private final String name;
    private final int numThreads;
    private final ExecutorService executorService;

    public TaskThreadPool(String name, int numThreads, MeterRegistry meterRegistry) {
        this.name = name;
        this.numThreads = numThreads;
        executorService = buildExecutorService(name, numThreads, meterRegistry);
    }

    @VisibleForTesting
    ExecutorService buildExecutorService(String name, int numThreads, MeterRegistry meterRegistry) {
        final ExecutorService executorService;
        executorService = ExecutorServiceMetrics.monitor(
                meterRegistry, Executors.newFixedThreadPool(numThreads, new NamedThreadFactory(name)),
                name,
                Collections.emptyList());
        return executorService;
    }

}

