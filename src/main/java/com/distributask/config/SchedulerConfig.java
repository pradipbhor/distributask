package com.distributask.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Configures the thread pool used by JobExecutor and enables Spring's
 * @Scheduled annotation processing for SchedulerEngine.
 *
 * ── Thread pool sizing ───────────────────────────────────────────────────
 *
 * corePoolSize   = always-warm threads, ready to accept jobs immediately
 * maxPoolSize    = peak capacity; extra threads are created on demand and
 *                  terminated after keepAliveSeconds of idle time
 * queueCapacity  = unbounded queue (Integer.MAX_VALUE) — we rely on the
 *                  token bucket to shed load before jobs reach the queue,
 *                  so the queue should never grow large
 *
 * Naming threads "job-executor-N" makes them identifiable in thread dumps.
 */
@Configuration
@EnableScheduling
public class SchedulerConfig {

    @Bean
    public ThreadPoolTaskExecutor taskExecutor(
            @Value("${scheduler.thread-pool.core-size:5}") int coreSize,
            @Value("${scheduler.thread-pool.max-size:20}") int maxSize) {

        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(coreSize);
        executor.setMaxPoolSize(maxSize);
        executor.setQueueCapacity(Integer.MAX_VALUE);
        executor.setThreadNamePrefix("job-executor-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.initialize();
        return executor;
    }
}
