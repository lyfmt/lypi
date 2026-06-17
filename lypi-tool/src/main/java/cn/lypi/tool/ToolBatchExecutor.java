package cn.lypi.tool;

import cn.lypi.contracts.tool.ToolResult;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

/**
 * 执行单个工具批次。
 */
final class ToolBatchExecutor {
    private final int maxConcurrency;

    ToolBatchExecutor(int maxConcurrency) {
        this.maxConcurrency = Math.max(1, maxConcurrency);
    }

    List<ToolResult<?>> execute(ToolExecutionPlanner.Batch batch, ToolCallExecutor executor) {
        if (!batch.parallel()) {
            return List.of(executor.execute(0, batch.calls().getFirst()));
        }
        try (BoundedVirtualExecutor virtualExecutor = new BoundedVirtualExecutor(maxConcurrency)) {
            List<CompletableFuture<IndexedResult>> futures = java.util.stream.IntStream.range(0, batch.calls().size())
                .mapToObj(index -> CompletableFuture.supplyAsync(
                    () -> new IndexedResult(index, executor.execute(index, batch.calls().get(index))),
                    virtualExecutor
                ))
                .toList();
            ToolResult<?>[] results = new ToolResult<?>[batch.calls().size()];
            for (CompletableFuture<IndexedResult> future : futures) {
                IndexedResult result = future.join();
                results[result.index()] = result.result();
            }
            return List.of(results);
        }
    }

    @FunctionalInterface
    interface ToolCallExecutor {
        ToolResult<?> execute(int index, ToolExecutionPlanner.ResolvedToolCall call);
    }

    private record IndexedResult(int index, ToolResult<?> result) {}

    private static final class BoundedVirtualExecutor implements ExecutorService, AutoCloseable {
        private final ExecutorService delegate;
        private final Semaphore semaphore;

        private BoundedVirtualExecutor(int maxConcurrency) {
            this.delegate = Executors.newVirtualThreadPerTaskExecutor();
            this.semaphore = new Semaphore(Math.max(1, maxConcurrency));
        }

        @Override
        public void execute(Runnable command) {
            delegate.execute(() -> {
                boolean acquired = false;
                try {
                    semaphore.acquire();
                    acquired = true;
                    command.run();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("工具并发执行被中断。", exception);
                } finally {
                    if (acquired) {
                        semaphore.release();
                    }
                }
            });
        }

        @Override
        public void close() {
            delegate.close();
        }

        @Override
        public void shutdown() {
            delegate.shutdown();
        }

        @Override
        public List<Runnable> shutdownNow() {
            return delegate.shutdownNow();
        }

        @Override
        public boolean isShutdown() {
            return delegate.isShutdown();
        }

        @Override
        public boolean isTerminated() {
            return delegate.isTerminated();
        }

        @Override
        public boolean awaitTermination(long timeout, java.util.concurrent.TimeUnit unit) throws InterruptedException {
            return delegate.awaitTermination(timeout, unit);
        }

        @Override
        public <T> java.util.concurrent.Future<T> submit(java.util.concurrent.Callable<T> task) {
            return delegate.submit(task);
        }

        @Override
        public <T> java.util.concurrent.Future<T> submit(Runnable task, T result) {
            return delegate.submit(task, result);
        }

        @Override
        public java.util.concurrent.Future<?> submit(Runnable task) {
            return delegate.submit(task);
        }

        @Override
        public <T> List<java.util.concurrent.Future<T>> invokeAll(
            java.util.Collection<? extends java.util.concurrent.Callable<T>> tasks
        ) throws InterruptedException {
            return delegate.invokeAll(tasks);
        }

        @Override
        public <T> List<java.util.concurrent.Future<T>> invokeAll(
            java.util.Collection<? extends java.util.concurrent.Callable<T>> tasks,
            long timeout,
            java.util.concurrent.TimeUnit unit
        ) throws InterruptedException {
            return delegate.invokeAll(tasks, timeout, unit);
        }

        @Override
        public <T> T invokeAny(java.util.Collection<? extends java.util.concurrent.Callable<T>> tasks)
            throws InterruptedException, java.util.concurrent.ExecutionException {
            return delegate.invokeAny(tasks);
        }

        @Override
        public <T> T invokeAny(
            java.util.Collection<? extends java.util.concurrent.Callable<T>> tasks,
            long timeout,
            java.util.concurrent.TimeUnit unit
        ) throws InterruptedException, java.util.concurrent.ExecutionException, java.util.concurrent.TimeoutException {
            return delegate.invokeAny(tasks, timeout, unit);
        }
    }
}
