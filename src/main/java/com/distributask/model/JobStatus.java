package com.distributask.model;

/**
 * Lifecycle states for a scheduled job.
 *
 * ACTIVE   — scheduler will pick this job up when its cron fires
 * PAUSED   — scheduler ignores this job; can be resumed via API
 * FAILED   — all retries exhausted; requires manual intervention
 * DELETED  — soft-deleted; hidden from listings but kept for audit history
 */
public enum JobStatus {
    ACTIVE,
    PAUSED,
    FAILED,
    DELETED
}
