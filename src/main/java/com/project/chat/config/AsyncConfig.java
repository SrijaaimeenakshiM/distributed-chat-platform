package com.project.chat.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * AsyncConfig establishes named thread pools for each concern in the system.
 * Separation ensures that a burst of DB writes cannot starve Redis publishes,
 * and neither can exhaust the presence-update pool.
 *
 * Three platform-thread pools + one virtual-thread executor cover every use case:
 *  - db-write      : blocking I/O → JDBC → platform threads sized to DB connection pool
 *  - redis-publish : non-blocking pub/sub → platform threads for predictable throughput
 *  - presence      : infrequent heartbeats → small platform pool
 *  - virtual-task  : general-purpose short-lived tasks → Java 21 virtual threads
 */
@Configuration
@Slf4j
public class AsyncConfig implements AsyncConfigurer {

    @Value("${app.thread-pools.db-write-core:5}")
    private int dbWriteCore;

    @Value("${app.thread-pools.db-write-max:20}")
    private int dbWriteMax;

    @Value("${app.thread-pools.redis-publish-core:5}")
    private int redisPublishCore;

    @Value("${app.thread-pools.redis-publish-max:15}")
    private int redisPublishMax;

    @Value("${app.thread-pools.presence-core:2}")
    private int presenceCore;

    @Value("${app.thread-pools.presence-max:8}")
    private int presenceMax;

    // ─── DB Write Pool ────────────────────────────────────────────────────────
    /**
     * Named "db-write" executor for @Async database persistence tasks.
     * Sized to match HikariCP's maximum-pool-size (20) so every thread
     * can have a DB connection without contention.
     * Uses a bounded queue (1000) to apply back-pressure rather than OOM.
     */
    @Bean(name = "dbWriteExecutor")
    public Executor dbWriteExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(dbWriteCore);
        executor.setMaxPoolSize(dbWriteMax);
        executor.setQueueCapacity(1000);
        executor.setThreadNamePrefix("db-write-");
        executor.setKeepAliveSeconds(60);
        executor.setAllowCoreThreadTimeOut(false);
        // CallerRunsPolicy: if queue is full, calling thread executes the task.
        // This provides natural back-pressure without dropping messages.
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        log.info("[AsyncConfig] db-write executor initialized: core={}, max={}", dbWriteCore, dbWriteMax);
        return executor;
    }

    // ─── Redis Publish Pool ────────────────────────────────────────────────────
    /**
     * Named "redis-publish" executor for @Async Redis pub/sub operations.
     * Separate from db-write to prevent a DB slowdown from blocking pub/sub.
     * Redis operations are typically fast (<1ms) so a smaller pool suffices.
     */
    @Bean(name = "redisPublishExecutor")
    public Executor redisPublishExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(redisPublishCore);
        executor.setMaxPoolSize(redisPublishMax);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("redis-publish-");
        executor.setKeepAliveSeconds(30);
        // AbortPolicy: if Redis publish queue overflows, fail fast and log.
        // We'd rather lose a pub/sub delivery than cascade failures.
        executor.setRejectedExecutionHandler((r, e) -> {
            log.error("[redis-publish] Queue overflow — dropping pub/sub task. " +
                    "Active={}, Queue={}", e.getActiveCount(), e.getQueue().size());
            throw new RejectedExecutionException("Redis publish queue full");
        });
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        executor.initialize();
        log.info("[AsyncConfig] redis-publish executor initialized: core={}, max={}", redisPublishCore, redisPublishMax);
        return executor;
    }

    // ─── Presence Pool ────────────────────────────────────────────────────────
    /**
     * Named "presence" executor for user presence heartbeat updates.
     * Low-volume, periodic tasks → small pool to minimize idle thread overhead.
     * Presence updates can be dropped under extreme load without data loss.
     */
    @Bean(name = "presenceExecutor")
    public Executor presenceExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(presenceCore);
        executor.setMaxPoolSize(presenceMax);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("presence-");
        executor.setKeepAliveSeconds(60);
        executor.setAllowCoreThreadTimeOut(true); // reclaim idle threads during quiet periods
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.DiscardOldestPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(false); // presence is best-effort
        executor.initialize();
        log.info("[AsyncConfig] presence executor initialized: core={}, max={}", presenceCore, presenceMax);
        return executor;
    }

    // ─── Virtual Thread Executor ───────────────────────────────────────────────
    /**
     * Java 21 Virtual Thread executor for short-lived, I/O-bound tasks.
     * Virtual threads are scheduled by the JVM onto platform carrier threads,
     * enabling millions of concurrent tasks without OS thread overhead.
     *
     * WHEN TO USE: tasks that block on I/O (HTTP calls, cache lookups)
     * but are not bounded by a fixed resource like DB connections.
     * DO NOT USE for CPU-intensive work (use platform threads instead).
     */
    @Bean(name = "virtualThreadExecutor")
    public ExecutorService virtualThreadExecutor() {
        log.info("[AsyncConfig] Java 21 virtual thread executor created");
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    /**
     * Default async executor for Spring's @Async without a specified executor.
     * Routes to the db-write pool as a safe default for unknown async tasks.
     */
    @Override
    public Executor getAsyncExecutor() {
        return dbWriteExecutor();
    }
}
