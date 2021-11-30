/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws;

import ai.asserts.aws.cloudwatch.config.ScrapeConfigProvider;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Component
@Getter
@Slf4j
public class TaskThreadPool {
    private final ScrapeConfigProvider scrapeConfigProvider;
    private final ExecutorService executorService;

    public TaskThreadPool(ScrapeConfigProvider scrapeConfigProvider) {
        this.scrapeConfigProvider = scrapeConfigProvider;
        Integer numTaskThreads = scrapeConfigProvider.getScrapeConfig().getNumTaskThreads();
        executorService = Executors.newFixedThreadPool(numTaskThreads);
    }
}

