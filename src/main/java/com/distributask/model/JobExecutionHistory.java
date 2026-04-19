package com.distributask.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * Immutable audit record written after every job execution attempt.
 *
 * One row per run. The JobExecutor inserts a row before the job starts
 * (status=RUNNING) and updates it when the job finishes or fails.
 * This gives us a full timeline even if the worker crashes mid-run.
 */
@Entity
@Table(name = "job_execution_history")
public class JobExecutionHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "job_id", nullable = false)
    private Long jobId;

    /** Hostname or container name of the worker that acquired the lock. */
    @Column(name = "worker_node", length = 100)
    private String workerNode;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    /** Wall-clock milliseconds from start to finish. */
    @Column(name = "duration_ms")
    private Long durationMs;

    /** "SUCCESS", "FAILED", or "RUNNING". */
    @Column(name = "status", length = 20)
    private String status;

    /** Stack trace or message captured from the exception, if any. */
    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    // ── getters / setters ────────────────────────────────────────────────────

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getJobId() { return jobId; }
    public void setJobId(Long jobId) { this.jobId = jobId; }

    public String getWorkerNode() { return workerNode; }
    public void setWorkerNode(String workerNode) { this.workerNode = workerNode; }

    public LocalDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(LocalDateTime startedAt) { this.startedAt = startedAt; }

    public LocalDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(LocalDateTime completedAt) { this.completedAt = completedAt; }

    public Long getDurationMs() { return durationMs; }
    public void setDurationMs(Long durationMs) { this.durationMs = durationMs; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
}
