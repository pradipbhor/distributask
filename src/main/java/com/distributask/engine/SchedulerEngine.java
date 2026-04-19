package com.distributask.engine;

import com.distributask.model.Job;
import com.distributask.model.JobStatus;
import com.distributask.repository.JobRepository;
import org.quartz.CronExpression;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;

/**
 * The heartbeat of the entire system.
 *
 * ── Polling loop ──────────────────────────────────────────────────────────
 *
 * Every 5 seconds (@Scheduled), this component:
 *   1. Queries MySQL for ACTIVE jobs whose next_run_at <= NOW()
 *   2. For each due job, submits it to the thread pool via JobExecutor
 *   3. Computes and persists the job's NEXT fire time from the cron expression
 *
 * ── Why polling instead of event-driven? ─────────────────────────────────
 *
 * Polling MySQL gives us a single source of truth. Any worker can pick up
 * any job; the Redis lock resolves the race. This is simpler than a
 * Kafka/RabbitMQ queue and still handles hundreds of jobs/minute easily.
 * For 10K+ jobs/minute, the polling interval can be tightened or replaced
 * with a push-based queue without changing the rest of the system.
 *
 * ── Cron resolution ──────────────────────────────────────────────────────
 *
 * We use Quartz's CronExpression parser because it supports the common
 * 6-field format (seconds minutes hours dom month dow). Spring's @Scheduled
 * also uses Quartz under the hood, but we need the "next fire time" value
 * as a persisted timestamp so all workers agree on when to wake up a job.
 */
@Component
public class SchedulerEngine {

    private static final Logger log = LoggerFactory.getLogger(SchedulerEngine.class);

    private final JobRepository           jobRepository;
    private final JobExecutor             jobExecutor;
    private final ThreadPoolTaskExecutor  taskExecutor;

    public SchedulerEngine(JobRepository jobRepository,
                           JobExecutor jobExecutor,
                           ThreadPoolTaskExecutor taskExecutor) {
        this.jobRepository = jobRepository;
        this.jobExecutor   = jobExecutor;
        this.taskExecutor  = taskExecutor;
    }

    /**
     * Main polling method — fires every 5,000 ms.
     *
     * fixedDelay (not fixedRate) means we wait 5s AFTER the previous poll
     * finishes, preventing overlapping polls if a poll takes longer than 5s.
     */
    @Scheduled(fixedDelayString = "${scheduler.poll-interval-ms:5000}")
    public void poll() {
        List<Job> dueJobs = jobRepository.findDueJobs(JobStatus.ACTIVE, LocalDateTime.now());

        if (dueJobs.isEmpty()) {
            log.debug("Poll: no due jobs");
            return;
        }

        log.info("Poll: {} due job(s) found", dueJobs.size());

        for (Job job : dueJobs) {
            // Advance next_run_at BEFORE dispatching so the next poll doesn't
            // pick up the same job again (even if this worker is slow to lock).
            advanceNextRunAt(job);
            jobRepository.save(job);

            // Submit to thread pool; JobExecutor handles lock + execution.
            taskExecutor.submit(() -> jobExecutor.execute(job));
        }
    }

    /**
     * Computes the next fire time for the given job using Quartz's
     * CronExpression and persists it back to the database.
     *
     * If the cron expression is invalid, the job is left with a null
     * next_run_at and will not be picked up again until an operator
     * corrects the expression.
     */
    private void advanceNextRunAt(Job job) {
        try {
            CronExpression cron = new CronExpression(job.getCronExpression());
            Date nextFire = cron.getNextValidTimeAfter(new Date());
            if (nextFire != null) {
                LocalDateTime next = nextFire.toInstant()
                    .atZone(ZoneId.systemDefault())
                    .toLocalDateTime();
                job.setNextRunAt(next);
                log.debug("Job {} '{}' next run at {}", job.getId(), job.getName(), next);
            }
        } catch (Exception e) {
            log.error("Invalid cron expression for job {} '{}': {}",
                      job.getId(), job.getName(), job.getCronExpression(), e);
            job.setNextRunAt(null);
        }
    }
}
