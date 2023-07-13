/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.lambda;

import ai.asserts.aws.AWSApiCallRateLimiter;
import ai.asserts.aws.cloudwatch.TimeWindowBuilder;
import ai.asserts.aws.config.LogScrapeConfig;
import com.google.common.collect.ImmutableSortedMap;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;
import software.amazon.awssdk.services.cloudwatchlogs.model.FilterLogEventsRequest;
import software.amazon.awssdk.services.cloudwatchlogs.model.FilterLogEventsResponse;
import software.amazon.awssdk.services.cloudwatchlogs.model.FilteredLogEvent;

import java.time.Instant;
import java.util.Optional;

import static ai.asserts.aws.MetricNameUtil.SCRAPE_ACCOUNT_ID_LABEL;
import static ai.asserts.aws.MetricNameUtil.SCRAPE_NAMESPACE_LABEL;
import static ai.asserts.aws.MetricNameUtil.SCRAPE_OPERATION_LABEL;
import static ai.asserts.aws.MetricNameUtil.SCRAPE_REGION_LABEL;
import static java.lang.String.format;

@Component
@Slf4j
@AllArgsConstructor
public class LogEventScraper {
    private final TimeWindowBuilder timeWindowBuilder;
    private final AWSApiCallRateLimiter rateLimiter;

    public Optional<FilteredLogEvent> findLogEvent(CloudWatchLogsClient cloudWatchLogsClient,
                                                   LambdaFunction functionConfig,
                                                   LogScrapeConfig logScrapeConfig) {
        Instant[] timePeriod = timeWindowBuilder.getTimePeriod(functionConfig.getRegion(), 60);
        Instant endTime = timePeriod[1].minusSeconds(60);
        Instant startTime = timePeriod[0].minusSeconds(60);
        String logGroupName = format("/aws/lambda/%s", functionConfig.getName());
        log.info("About to scrape logs from {}", logGroupName);
        try {
            FilterLogEventsRequest logEventsRequest = FilterLogEventsRequest.builder()
                    .limit(1)
                    .startTime(startTime.toEpochMilli())
                    .endTime(endTime.toEpochMilli())
                    .filterPattern(logScrapeConfig.getLogFilterPattern())
                    .logGroupName(logGroupName)
                    .build();

            FilterLogEventsResponse response = rateLimiter.doWithRateLimit(
                    "CloudWatchLogsClient/filterLogEvents",
                    ImmutableSortedMap.of(
                            SCRAPE_ACCOUNT_ID_LABEL, functionConfig.getAccount(),
                            SCRAPE_REGION_LABEL, functionConfig.getRegion(),
                            SCRAPE_OPERATION_LABEL, "filterLogEvents",
                            SCRAPE_NAMESPACE_LABEL, "AWS/Lambda"
                    ), () -> cloudWatchLogsClient.filterLogEvents(logEventsRequest));

            log.info("log scrape config {} matched {} events", logScrapeConfig, getCount(response));
            if (response.hasEvents()) {
                return response.events().stream().findFirst();
            }
        } catch (Exception e) {
            log.error("Failed to scrape logs from log group " + logGroupName, e);
        }
        return Optional.empty();
    }

    private int getCount(FilterLogEventsResponse response) {
        return CollectionUtils.isEmpty(response.events()) ? 0 : response.events().size();
    }
}
