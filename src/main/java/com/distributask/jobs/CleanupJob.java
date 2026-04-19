package com.distributask.jobs;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Example job: simulates purging stale temporary files or expired records.
 *
 * Register via API:
 *   POST /api/jobs
 *   { "name": "nightly-cleanup", "cronExpression": "0 0 2 * * ?",
 *     "jobClass": "com.distributask.jobs.CleanupJob", "maxRetries": 1 }
 */
@Component
public class CleanupJob implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(CleanupJob.class);

    @Override
    public void run() {
        log.info("CleanupJob: purging stale records...");
        try { Thread.sleep(200); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        log.info("CleanupJob: cleanup complete");
    }
}
