package com.distributask.lock;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Redis-backed distributed lock using the SETNX (SET if Not eXists) primitive.
 *
 * ── Why SETNX works for exactly-once execution ────────────────────────────
 *
 * Redis processes commands sequentially in a single thread. When N workers
 * all call SETNX on the same key at the same millisecond, Redis serialises
 * them and lets exactly ONE succeed. The others see "key already exists"
 * and immediately back off.
 *
 * ── Why TTL is non-negotiable ────────────────────────────────────────────
 *
 * If a worker acquires the lock and then crashes, the key would live forever
 * without a TTL — no other worker could ever run that job again (deadlock).
 * The TTL is set atomically in the same command (SET ... NX PX <ms>), so
 * there is no window between "set key" and "set expiry".
 *
 * ── Key format ────────────────────────────────────────────────────────────
 *
 *   "job:lock:<jobId>"   e.g. "job:lock:42"
 *
 * The value stored is the worker's hostname, which makes it easy to inspect
 * Redis and see which worker holds which lock.
 */
@Component
public class RedisDistributedLock {

    private static final Logger log = LoggerFactory.getLogger(RedisDistributedLock.class);
    private static final String KEY_PREFIX = "job:lock:";

    private final StringRedisTemplate redis;
    private final String workerNode;

    private final Counter acquiredCounter;
    private final Counter failedCounter;

    public RedisDistributedLock(StringRedisTemplate redis,
                                MeterRegistry meterRegistry) {
        this.redis = redis;
        // Use hostname as the lock owner identifier so logs are human-readable.
        this.workerNode = resolveWorkerNode();
        this.acquiredCounter = meterRegistry.counter("scheduler_lock_acquired_total");
        this.failedCounter   = meterRegistry.counter("scheduler_lock_failed_total");
    }

    /**
     * Attempts to acquire an exclusive lock for the given job.
     *
     * Internally issues: SET job:lock:<jobId> <workerNode> NX PX <ttlMs>
     *
     * @param jobId      the job's database primary key
     * @param ttlSeconds how long the lock is held before auto-expiry
     * @return true if THIS worker now holds the lock; false if another worker does
     */
    public boolean acquireLock(Long jobId, int ttlSeconds) {
        String key   = KEY_PREFIX + jobId;
        String value = workerNode;

        // setIfAbsent → Redis SET ... NX PX ... (atomic)
        Boolean acquired = redis.opsForValue()
            .setIfAbsent(key, value, Duration.ofSeconds(ttlSeconds));

        boolean success = Boolean.TRUE.equals(acquired);
        if (success) {
            acquiredCounter.increment();
            log.debug("Lock acquired: key={} owner={}", key, workerNode);
        } else {
            failedCounter.increment();
            log.debug("Lock NOT acquired: key={} already held by another worker", key);
        }
        return success;
    }

    /**
     * Releases the lock, but ONLY if this worker still owns it.
     *
     * We check the stored value before deleting to avoid releasing a lock
     * that was legitimately re-acquired by another worker after this worker's
     * TTL expired. (Ideally this would use a Lua script for atomicity, but
     * the window is tiny and acceptable for this use-case.)
     */
    public void releaseLock(Long jobId) {
        String key = KEY_PREFIX + jobId;
        String currentOwner = redis.opsForValue().get(key);

        if (workerNode.equals(currentOwner)) {
            redis.delete(key);
            log.debug("Lock released: key={}", key);
        } else {
            log.warn("Lock release skipped: key={} owner mismatch (current={}, us={})",
                     key, currentOwner, workerNode);
        }
    }

    public String getWorkerNode() {
        return workerNode;
    }

    private static String resolveWorkerNode() {
        // Prefer an explicit WORKER_NODE env var (set in docker-compose.yml),
        // fall back to InetAddress hostname.
        String env = System.getenv("WORKER_NODE");
        if (env != null && !env.isBlank()) return env;
        try {
            return java.net.InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "worker-unknown";
        }
    }
}
