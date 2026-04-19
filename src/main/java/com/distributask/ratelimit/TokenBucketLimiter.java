package com.distributask.ratelimit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.Semaphore;

/**
 * Per-node token bucket rate limiter using a JVM Semaphore.
 *
 * ── Why per-node, not global? ─────────────────────────────────────────────
 *
 * Each worker independently limits how many jobs it runs concurrently.
 * A global Redis-based counter could enforce a cluster-wide ceiling, but
 * that adds latency on the hot path and isn't necessary for this use-case.
 *
 * ── How it works ─────────────────────────────────────────────────────────
 *
 * Think of it as a bucket with N tokens. Each job execution consumes one
 * token before running and returns it when done. If all tokens are
 * consumed (i.e. N jobs are running simultaneously), any new job is
 * SKIPPED on this polling cycle — it will be picked up on the next poll.
 *
 * This prevents a single worker from spawning unbounded goroutines and
 * exhausting its thread pool when many jobs are simultaneously due.
 *
 * ── Configuration ─────────────────────────────────────────────────────────
 *
 *   scheduler.rate-limit.max-concurrent-jobs=10   (default)
 */
@Component
public class TokenBucketLimiter {

    private static final Logger log = LoggerFactory.getLogger(TokenBucketLimiter.class);

    private final Semaphore semaphore;
    private final int maxConcurrentJobs;

    public TokenBucketLimiter(
            @Value("${scheduler.rate-limit.max-concurrent-jobs:10}") int maxConcurrentJobs) {
        this.maxConcurrentJobs = maxConcurrentJobs;
        // fair=false: no FIFO ordering needed; any waiting thread may proceed
        this.semaphore = new Semaphore(maxConcurrentJobs, false);
        log.info("TokenBucketLimiter initialised: maxConcurrentJobs={}", maxConcurrentJobs);
    }

    /**
     * Tries to consume one token (non-blocking).
     *
     * @return true if a token was available and consumed; false if rate limit hit
     */
    public boolean tryAcquire() {
        boolean acquired = semaphore.tryAcquire();
        if (!acquired) {
            log.warn("Rate limit reached ({}/{}), skipping job dispatch on this cycle",
                     maxConcurrentJobs - semaphore.availablePermits(), maxConcurrentJobs);
        }
        return acquired;
    }

    /** Returns the consumed token so the next job can use it. */
    public void release() {
        semaphore.release();
    }

    public int availablePermits() {
        return semaphore.availablePermits();
    }
}
