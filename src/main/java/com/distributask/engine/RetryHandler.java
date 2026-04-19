package com.distributask.engine;

import com.distributask.model.Job;
import com.distributask.model.JobStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * Decides what happens to a job after an execution failure.
 *
 * ── Exponential backoff formula ───────────────────────────────────────────
 *
 *   delay = 2^retryCount × 10 seconds
 *
 *   retryCount=1 → 2^1 × 10 = 20 s
 *   retryCount=2 → 2^2 × 10 = 40 s
 *   retryCount=3 → 2^3 × 10 = 80 s
 *
 * This mirrors the pattern used in AWS SDK, Kafka consumer retry, and
 * Spring Retry. The growing delay gives a failing downstream service
 * breathing room to recover instead of hammering it at a fixed interval.
 *
 * ── When retries are exhausted ────────────────────────────────────────────
 *
 * Once retryCount > maxRetries, the job moves to FAILED status and is no
 * longer polled by the scheduler. An operator must either fix the root
 * cause and manually resume the job, or delete it.
 */
@Component
public class RetryHandler {

    private static final Logger log = LoggerFactory.getLogger(RetryHandler.class);

    /**
     * Called by JobExecutor when a job throws an exception.
     * Mutates the job entity in place; the caller is responsible for
     * persisting the changes.
     *
     * @param job the job that just failed
     * @param e   the exception that caused the failure
     */
    public void handleFailure(Job job, Exception e) {
        job.setRetryCount(job.getRetryCount() + 1);

        if (job.getRetryCount() <= job.getMaxRetries()) {
            // Exponential backoff: schedule the next retry attempt
            long delaySeconds = (long) Math.pow(2, job.getRetryCount()) * 10;
            job.setNextRunAt(LocalDateTime.now().plusSeconds(delaySeconds));
            job.setLastRunStatus("FAILED");

            log.warn("Job {} '{}' failed (attempt {}/{}), retrying in {}s — {}",
                     job.getId(), job.getName(),
                     job.getRetryCount(), job.getMaxRetries(),
                     delaySeconds, e.getMessage());
        } else {
            // All retries exhausted — move to terminal FAILED state
            job.setStatus(JobStatus.FAILED);
            job.setLastRunStatus("FAILED");

            log.error("Job {} '{}' exhausted all {} retries, marking as FAILED — {}",
                      job.getId(), job.getName(), job.getMaxRetries(), e.getMessage());
        }
    }

    /**
     * Called by JobExecutor when a job succeeds.
     * Resets the retry counter so a later transient failure starts fresh.
     */
    public void handleSuccess(Job job) {
        job.setRetryCount(0);
        job.setLastRunStatus("SUCCESS");
    }
}
