package com.distributask.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * Persistent definition of a scheduled job.
 *
 * A Job row answers: "what should run, when, and how many times may it retry?"
 * The scheduler engine reads ACTIVE rows whose next_run_at <= NOW() and
 * dispatches them. The engine writes back last_run_at, last_run_status,
 * next_run_at (after computing the next cron fire), and retry_count.
 */
@Entity
@Table(name = "jobs")
public class Job {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    /**
     * Standard 6-field cron expression understood by Quartz:
     *   seconds minutes hours day-of-month month day-of-week
     * Example: "0 0 9 * * ?" fires at 09:00:00 every day.
     */
    @Column(name = "cron_expression", nullable = false, length = 100)
    private String cronExpression;

    /**
     * Fully qualified class name of the Runnable to instantiate and run.
     * Example: "com.distributask.jobs.DailyReportJob"
     * The JobExecutor resolves this via the Spring ApplicationContext.
     */
    @Column(name = "job_class", nullable = false, length = 500)
    private String jobClass;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private JobStatus status = JobStatus.ACTIVE;

    /** When the scheduler should next execute this job. */
    @Column(name = "next_run_at")
    private LocalDateTime nextRunAt;

    @Column(name = "last_run_at")
    private LocalDateTime lastRunAt;

    @Column(name = "last_run_status", length = 20)
    private String lastRunStatus;

    /** Maximum number of retry attempts before status moves to FAILED. */
    @Column(name = "max_retries")
    private int maxRetries = 3;

    /** Current consecutive failure count; reset to 0 on success. */
    @Column(name = "retry_count")
    private int retryCount = 0;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    // ── getters / setters ────────────────────────────────────────────────────

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getCronExpression() { return cronExpression; }
    public void setCronExpression(String cronExpression) { this.cronExpression = cronExpression; }

    public String getJobClass() { return jobClass; }
    public void setJobClass(String jobClass) { this.jobClass = jobClass; }

    public JobStatus getStatus() { return status; }
    public void setStatus(JobStatus status) { this.status = status; }

    public LocalDateTime getNextRunAt() { return nextRunAt; }
    public void setNextRunAt(LocalDateTime nextRunAt) { this.nextRunAt = nextRunAt; }

    public LocalDateTime getLastRunAt() { return lastRunAt; }
    public void setLastRunAt(LocalDateTime lastRunAt) { this.lastRunAt = lastRunAt; }

    public String getLastRunStatus() { return lastRunStatus; }
    public void setLastRunStatus(String lastRunStatus) { this.lastRunStatus = lastRunStatus; }

    public int getMaxRetries() { return maxRetries; }
    public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }

    public int getRetryCount() { return retryCount; }
    public void setRetryCount(int retryCount) { this.retryCount = retryCount; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
