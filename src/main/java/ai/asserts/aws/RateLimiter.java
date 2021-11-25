/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws;

import com.google.common.collect.ImmutableMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;
import software.amazon.awssdk.services.ecs.EcsClient;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.resourcegroupstaggingapi.ResourceGroupsTaggingApiClient;

import java.util.Map;
import java.util.concurrent.Semaphore;

@Component
@Slf4j
public class RateLimiter {
    private final Semaphore defaultSemaphore = new Semaphore(2);
    private final Map<String, Semaphore> semaphores = ImmutableMap.of(
            LambdaClient.class.getSimpleName(), new Semaphore(2),
            EcsClient.class.getSimpleName(), new Semaphore(2),
            CloudWatchClient.class.getSimpleName(), new Semaphore(2),
            ResourceGroupsTaggingApiClient.class.getSimpleName(), new Semaphore(2),
            CloudWatchLogsClient.class.getSimpleName(), new Semaphore(1)
    );

    public <K extends AWSAPICall<V>, V> V doWithRateLimit(String api, K k) {
        Semaphore theSemaphore = semaphores.getOrDefault(api.split("/")[0], defaultSemaphore);
        try {
            theSemaphore.acquire();
            V returnValue = k.makeCall();
            if (api.contains(CloudWatchLogsClient.class.getSimpleName())) {
                sleep(5000);
            }
            return returnValue;
        } catch (InterruptedException e) {
            log.error("Interrupted Exception", e);
            throw new RuntimeException(e);
        } finally {
            theSemaphore.release();
        }
    }

    public interface AWSAPICall<V> {
        V makeCall();
    }

    private void sleep(long time) {
        try {
            Thread.sleep(time);
        } catch (InterruptedException e) {
            log.error("Interrupted", e);
        }
    }
}
