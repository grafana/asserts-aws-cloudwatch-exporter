/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws;

import ai.asserts.aws.account.AWSAccount;
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

    private final AWSApiCallRateLimiter rateLimiter;
    private static final ThreadLocal<AWSAccount> accountDetails = new ThreadLocal<>();


    public TaskExecutorUtil(@Qualifier("aws-api-calls-thread-pool") TaskThreadPool taskThreadPool, AWSApiCallRateLimiter rateLimiter) {
        this.taskThreadPool = taskThreadPool;
        this.rateLimiter = rateLimiter;
    }

    public <T> Future<T> executeAccountTask(AWSAccount accountDetails, TenantTask<T> task) {
        return taskThreadPool.getExecutorService().submit(() -> {
            TaskExecutorUtil.accountDetails.set(accountDetails);
            try {
                return rateLimiter.call(task);
            } catch (Exception e) {
                log.error("Failed to execute tenant task for tenant:" + accountDetails, e);
                return task.getReturnValueWhenError();
            } finally {
                TaskExecutorUtil.accountDetails.remove();
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

    public AWSAccount getAccountDetails() {
        return accountDetails.get();
    }
}
