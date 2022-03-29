/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws;

import ai.asserts.aws.cloudwatch.config.ScrapeConfigProvider;
import com.google.common.annotations.VisibleForTesting;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.jvm.ExecutorServiceMetrics;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Component
@Getter
@Slf4j
public class TaskThreadPool {
    private final ScrapeConfigProvider scrapeConfigProvider;
    private final ExecutorService executorService;

    public TaskThreadPool(ScrapeConfigProvider scrapeConfigProvider, MeterRegistry meterRegistry) {
        this.scrapeConfigProvider = scrapeConfigProvider;
        Integer numTaskThreads = scrapeConfigProvider.getScrapeConfig().getNumTaskThreads();
        executorService = executorService(meterRegistry, numTaskThreads);
    }

    @VisibleForTesting
    ExecutorService executorService(MeterRegistry meterRegistry, Integer numTaskThreads) {
        return ExecutorServiceMetrics.monitor(
                meterRegistry, Executors.newFixedThreadPool(numTaskThreads),
                "aws-api-calls-threadpool",
                Collections.emptyList());
    }
}

