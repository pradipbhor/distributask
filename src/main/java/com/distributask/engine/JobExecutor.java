package com.distributask.engine;

import com.distributask.lock.RedisDistributedLock;
import com.distributask.model.Job;
import com.distributask.model.JobExecutionHistory;
import com.distributask.ratelimit.TokenBucketLimiter;
import com.distributask.repository.JobHistoryRepository;
import com.distributask.repository.JobRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/**
 * Executes a single job asynchronously in a managed thread pool.
 *
 * ── Execution lifecycle ───────────────────────────────────────────────────
 *
 *  1. Check rate limit (token bucket) — skip if full
 *  2. Acquire Redis distributed lock   — skip if another worker got there first
 *  3. Persist a RUNNING history record (crash-safe audit trail)
 *  4. Resolve the Runnable from the Spring ApplicationContext
 *  5. Run it inside a Timer (Prometheus metric)
 *  6. On success: update history + job metadata + release lock
 *  7. On failure: update history + call RetryHandler + release lock
 *
 * ── Thread pool ───────────────────────────────────────────────────────────
 *
 * The SchedulerConfig defines a fixed ThreadPoolTaskExecutor. Each call to
 * execute() submits a Runnable to that pool so the main polling thread is
 * never blocked by job execution. The token bucket acts as a backstop if
 * the pool's queue is full.
 */
@Component
public class JobExecutor {

    private static final Logger log = LoggerFactory.getLogger(JobExecutor.class);

    // Redis lock TTL: must be longer than the longest expected job duration.
    // 5 minutes is a safe default; adjust per job if needed.
    private static final int LOCK_TTL_SECONDS = 300;

    private final RedisDistributedLock distributedLock;
    private final TokenBucketLimiter   rateLimiter;
    private final RetryHandler         retryHandler;
    private final JobRepository        jobRepository;
    private final JobHistoryRepository historyRepository;
    private final ApplicationContext   applicationContext;

    private final Counter executedCounter;
    private final Counter retryCounter;
    private final Timer   jobTimer;

    public JobExecutor(RedisDistributedLock distributedLock,
                       TokenBucketLimiter rateLimiter,
                       RetryHandler retryHandler,
                       JobRepository jobRepository,
                       JobHistoryRepository historyRepository,
                       ApplicationContext applicationContext,
                       MeterRegistry meterRegistry) {
        this.distributedLock    = distributedLock;
        this.rateLimiter        = rateLimiter;
        this.retryHandler       = retryHandler;
        this.jobRepository      = jobRepository;
        this.historyRepository  = historyRepository;
        this.applicationContext = applicationContext;

        this.executedCounter = meterRegistry.counter("scheduler_jobs_executed_total");
        this.retryCounter    = meterRegistry.counter("scheduler_retry_total");
        this.jobTimer        = meterRegistry.timer("scheduler_job_duration_seconds");
    }

    /**
     * Entry point called by SchedulerEngine for each due job.
     * This method is designed to be submitted to a thread pool.
     */
    public void execute(Job job) {
        // ── Step 1: rate limit ───────────────────────────────────────────
        if (!rateLimiter.tryAcquire()) {
            log.info("Rate limit hit, skipping job {} '{}' this cycle", job.getId(), job.getName());
            return;
        }

        try {
            // ── Step 2: acquire distributed lock ────────────────────────
            if (!distributedLock.acquireLock(job.getId(), LOCK_TTL_SECONDS)) {
                log.debug("Job {} '{}' already claimed by another worker", job.getId(), job.getName());
                return;
            }

            runWithLock(job);

        } finally {
            // Token is always returned, even if the lock was not acquired.
            rateLimiter.release();
        }
    }

    /** Called only when THIS worker holds the lock. */
    private void runWithLock(Job job) {
        // ── Step 3: write RUNNING history record ────────────────────────
        JobExecutionHistory history = new JobExecutionHistory();
        history.setJobId(job.getId());
        history.setWorkerNode(distributedLock.getWorkerNode());
        history.setStartedAt(LocalDateTime.now());
        history.setStatus("RUNNING");
        historyRepository.save(history);

        LocalDateTime startTime = LocalDateTime.now();

        try {
            // ── Step 4: resolve the job bean from Spring context ─────────
            Runnable jobBean = resolveJobBean(job.getJobClass());

            // ── Step 5: run it (timed for Prometheus) ───────────────────
            jobTimer.record(jobBean::run);

            // ── Step 6: success path ─────────────────────────────────────
            long durationMs = ChronoUnit.MILLIS.between(startTime, LocalDateTime.now());
            history.setCompletedAt(LocalDateTime.now());
            history.setDurationMs(durationMs);
            history.setStatus("SUCCESS");
            historyRepository.save(history);

            retryHandler.handleSuccess(job);
            job.setLastRunAt(startTime);
            jobRepository.save(job);

            executedCounter.increment();
            log.info("Job {} '{}' completed in {}ms on {}", job.getId(), job.getName(),
                     durationMs, distributedLock.getWorkerNode());

        } catch (Exception e) {
            // ── Step 7: failure path ─────────────────────────────────────
            long durationMs = ChronoUnit.MILLIS.between(startTime, LocalDateTime.now());
            history.setCompletedAt(LocalDateTime.now());
            history.setDurationMs(durationMs);
            history.setStatus("FAILED");
            history.setErrorMessage(e.getMessage());
            historyRepository.save(history);

            retryHandler.handleFailure(job, e);
            job.setLastRunAt(startTime);
            jobRepository.save(job);

            retryCounter.increment();
            log.error("Job {} '{}' failed after {}ms", job.getId(), job.getName(), durationMs, e);

        } finally {
            distributedLock.releaseLock(job.getId());
        }
    }

    /**
     * Resolves the Runnable implementation from the Spring ApplicationContext
     * by class name. This avoids reflection-based instantiation and lets
     * Spring inject dependencies into the job class normally.
     *
     * Example: "com.distributask.jobs.DailyReportJob" → DailyReportJob bean
     */
    @SuppressWarnings("unchecked")
    private Runnable resolveJobBean(String jobClassName) throws Exception {
        try {
            Class<?> clazz = Class.forName(jobClassName);
            return (Runnable) applicationContext.getBean(clazz);
        } catch (ClassNotFoundException e) {
            throw new IllegalArgumentException("Unknown job class: " + jobClassName, e);
        } catch (ClassCastException e) {
            throw new IllegalArgumentException(
                "Job class must implement Runnable: " + jobClassName, e);
        }
    }
}
