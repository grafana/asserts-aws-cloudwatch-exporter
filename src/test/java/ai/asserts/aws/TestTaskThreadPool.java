/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws;

import io.micrometer.core.instrument.MeterRegistry;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class TestTaskThreadPool extends TaskThreadPool {
    private final ExecutorService executorService = new SimpleExecutorService();

    public TestTaskThreadPool() {
        super("test pool", 1, null);
    }

    @Override
    ExecutorService buildExecutorService(String name, int nThreads, MeterRegistry meterRegistry) {
        return executorService;
    }

    public static class MockFuture<T> implements Future<T> {
        private final T t;

        public MockFuture(T t) {
            this.t = t;
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            return false;
        }

        @Override
        public boolean isCancelled() {
            return false;
        }

        @Override
        public boolean isDone() {
            return false;
        }

        @Override
        public T get() throws InterruptedException, ExecutionException {
            return t;
        }

        @Override
        public T get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
            return t;
        }
    }

    @Override
    public ExecutorService getExecutorService() {
        return executorService;
    }


    @SuppressWarnings("all")
    public static class SimpleExecutorService implements ExecutorService {
        @Override
        public void shutdown() {

        }

        @Override
        public List<Runnable> shutdownNow() {
            return null;
        }

        @Override
        public boolean isShutdown() {
            return false;
        }

        @Override
        public boolean isTerminated() {
            return false;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
            return false;
        }

        @Override
        public <T> Future<T> submit(Callable<T> task) {
            try {
                return new MockFuture<>(task.call());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public <T> Future<T> submit(Runnable task, T result) {
            return null;
        }

        @Override
        public Future<?> submit(Runnable task) {
            task.run();
            return new MockFuture<>(null);
        }

        @Override
        public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks) throws InterruptedException {
            return null;
        }

        @Override
        public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit) throws InterruptedException {
            return null;
        }

        @Override
        public <T> T invokeAny(Collection<? extends Callable<T>> tasks) throws InterruptedException,
                ExecutionException {
            return null;
        }

        @Override
        public <T> T invokeAny(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
            return null;
        }

        @Override
        public void execute(Runnable command) {

        }
    }
}
