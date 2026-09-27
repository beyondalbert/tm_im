package com.tm.im.core.plaza;

import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/**
 * 「就地执行」的线程池替身：{@code execute} 直接在当前线程跑任务。
 *
 * <p>为什么不用 {@code Executors.newSingleThreadExecutor()}：那样写扩散会真的异步，
 * 于是一次断言就变成一场竞态（断言跑在扩散之前还是之后取决于调度）。
 * 而这里要测的规则是「一条动态给了哪些人各写一行收件箱」——那是<b>纯逻辑</b>，
 * 与并发无关。真并发（队列满、丢弃、提交失败）由 {@code PlazaServiceTest}
 * 里单独的一条用真实的 {@code ThreadPoolExecutor} 覆盖。
 */
class InlineExecutor extends AbstractExecutorService {

    private volatile boolean shutdown;

    @Override
    public void execute(Runnable command) {
        if (shutdown) {
            throw new java.util.concurrent.RejectedExecutionException("替身：已关闭");
        }
        command.run();
    }

    @Override
    public void shutdown() {
        shutdown = true;
    }

    @Override
    public List<Runnable> shutdownNow() {
        shutdown = true;
        return List.of();
    }

    @Override
    public boolean isShutdown() {
        return shutdown;
    }

    @Override
    public boolean isTerminated() {
        return shutdown;
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) {
        return shutdown;
    }

    @Override
    public <T> java.util.concurrent.Future<T> submit(Callable<T> task) {
        FutureTask<T> future = new FutureTask<>(task);
        execute(future);
        return future;
    }

    @Override
    public java.util.concurrent.Future<?> submit(Runnable task) {
        FutureTask<Object> future = new FutureTask<>(task, null);
        execute(future);
        return future;
    }
}
