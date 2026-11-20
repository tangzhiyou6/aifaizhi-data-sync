package com.hd.sync.config;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 同步任务线程池：单线程串行执行，保证同一时刻只有一个同步任务在跑。
 */
@Configuration
public class SyncExecutorConfig {

    @Bean(name = "syncTaskExecutor", destroyMethod = "shutdown")
    public ExecutorService syncTaskExecutor() {
        AtomicInteger counter = new AtomicInteger(1);
        ThreadFactory threadFactory = runnable -> {
            Thread thread = new Thread(runnable, "data-sync-task-" + counter.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        };
        return Executors.newSingleThreadExecutor(threadFactory);
    }
}
