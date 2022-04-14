/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.config;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class LogScrapeConfigTest {
    private LogScrapeConfig logScrapeConfig;

    @BeforeEach
    public void setup() {
        logScrapeConfig = LogScrapeConfig.builder()
                .lambdaFunctionName("Function.+")
                .logFilterPattern("logFilter")
                .regexPattern("put (.+?) in (.+?) queue")
                .functionNames(ImmutableList.of("Function-From-List"))
                .labels(ImmutableMap.of(
                        "message_type", "$1",
                        "queue_name", "$2"
                ))
                .sampleLogMessage("put OrderRequest in Request queue")
                .sampleExpectedLabels(ImmutableMap.of(
                        "d_message_type", "OrderRequest",
                        "d_queue_name", "Request"
                ))
                .build();
    }

    @Test
    void initialize_valid() {
        logScrapeConfig.initialize();
        assertTrue(logScrapeConfig.isValid());
    }

    @Test
    void initialize_invalid() {
        LogScrapeConfig config = LogScrapeConfig.builder().build();
        config.initialize();
        assertFalse(config.isValid());
    }

    @Test
    void shouldScrapeLogsFor_PatternMatch() {
        logScrapeConfig.initialize();
        assertTrue(logScrapeConfig.shouldScrapeLogsFor("Function-8-to-9"));
        assertFalse(logScrapeConfig.shouldScrapeLogsFor("A-Function-8-to-9"));
    }

    @Test
    void shouldScrapeLogsFor_ListMatch() {
        logScrapeConfig.initialize();
        assertTrue(logScrapeConfig.shouldScrapeLogsFor("Function-From-List"));
    }

    @Test
    void extractLabels_Match() {
        logScrapeConfig.initialize();
        assertTrue(logScrapeConfig.isValid());
        assertEquals(
                ImmutableMap.of(
                        "d_message_type", "CancellationRequest",
                        "d_queue_name", "Cancellation"
                ),
                logScrapeConfig.extractLabels("put CancellationRequest in Cancellation queue")
        );
    }

    @Test
    void extractLabels_no_Match() {
        logScrapeConfig.initialize();
        assertTrue(logScrapeConfig.isValid());
        assertEquals(
                ImmutableMap.of(),
                logScrapeConfig.extractLabels("put CancellationRequest in Cancellation QUEUE")
        );
    }
}
