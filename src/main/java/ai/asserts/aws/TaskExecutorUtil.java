/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

@Component
@Slf4j
public class TaskExecutorUtil {
    private final TaskThreadPool taskThreadPool;

    private final RateLimiter rateLimiter;
    private static final ThreadLocal<String> tenantName = new ThreadLocal<>();


    public TaskExecutorUtil(@Qualifier("aws-api-calls-thread-pool") TaskThreadPool taskThreadPool, RateLimiter rateLimiter) {
        this.taskThreadPool = taskThreadPool;
        this.rateLimiter = rateLimiter;
    }

    public <T> Future<T> executeTenantTask(String tenant, TenantTask<T> task) {
        return taskThreadPool.getExecutorService().submit(() -> {
            tenantName.set(tenant);
            try {
                return rateLimiter.call(task);
            } catch (Exception e) {
                log.error("Failed to execute tenant task for tenant:" + tenant, e);
                return task.getReturnValueWhenError();
            } finally {
                tenantName.remove();
            }
        });
    }

    public <K> void awaitAll(List<Future<K>> futures, Consumer<K> consumer) {
        futures.forEach(f -> {
            try {
                consumer.accept(f.get(30, TimeUnit.SECONDS));
            } catch (InterruptedException | ExecutionException | TimeoutException e) {
                log.error("AWS API call error ", e);
            }
        });
    }

    public String getTenant() {
        return tenantName.get();
    }
}
