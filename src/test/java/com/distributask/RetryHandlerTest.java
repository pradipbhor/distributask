package com.distributask;

import com.distributask.engine.RetryHandler;
import com.distributask.model.Job;
import com.distributask.model.JobStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for RetryHandler's exponential backoff and terminal-failure logic.
 *
 * Key invariants under test:
 *  1. retryCount increments on each failure
 *  2. next_run_at is set to NOW + 2^n * 10s (within a small tolerance)
 *  3. status moves to FAILED only when retryCount > maxRetries
 *  4. retryCount resets to 0 on success
 */
class RetryHandlerTest {

    private RetryHandler retryHandler;
    private Job job;

    @BeforeEach
    void setup() {
        retryHandler = new RetryHandler();
        job = new Job();
        job.setId(1L);
        job.setName("test-job");
        job.setMaxRetries(3);
        job.setRetryCount(0);
        job.setStatus(JobStatus.ACTIVE);
    }

    @Test
    void firstFailure_schedulesRetry_withCorrectDelay() {
        LocalDateTime before = LocalDateTime.now();

        retryHandler.handleFailure(job, new RuntimeException("timeout"));

        assertEquals(1, job.getRetryCount());
        assertEquals(JobStatus.ACTIVE, job.getStatus());   // not yet FAILED
        assertEquals("FAILED", job.getLastRunStatus());

        // Expected delay: 2^1 * 10 = 20s
        LocalDateTime expectedNext = before.plusSeconds(20);
        assertNotNull(job.getNextRunAt());
        // Allow 2 second tolerance for test execution time
        assertTrue(job.getNextRunAt().isAfter(expectedNext.minusSeconds(2)));
        assertTrue(job.getNextRunAt().isBefore(expectedNext.plusSeconds(2)));
    }

    @Test
    void secondFailure_doublesDelay() {
        job.setRetryCount(1); // simulate first retry already happened
        LocalDateTime before = LocalDateTime.now();

        retryHandler.handleFailure(job, new RuntimeException("timeout"));

        assertEquals(2, job.getRetryCount());
        // Expected delay: 2^2 * 10 = 40s
        LocalDateTime expectedNext = before.plusSeconds(40);
        assertTrue(job.getNextRunAt().isAfter(expectedNext.minusSeconds(2)));
        assertTrue(job.getNextRunAt().isBefore(expectedNext.plusSeconds(2)));
    }

    @Test
    void exhaustedRetries_movesJobToFailedStatus() {
        job.setRetryCount(3); // already at maxRetries

        retryHandler.handleFailure(job, new RuntimeException("fatal error"));

        assertEquals(JobStatus.FAILED, job.getStatus());
        assertEquals(4, job.getRetryCount());
    }

    @Test
    void handleSuccess_resetsRetryCount() {
        job.setRetryCount(2);

        retryHandler.handleSuccess(job);

        assertEquals(0, job.getRetryCount());
        assertEquals("SUCCESS", job.getLastRunStatus());
    }
}
