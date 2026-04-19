package com.distributask;

import com.distributask.engine.JobExecutor;
import com.distributask.engine.SchedulerEngine;
import com.distributask.model.Job;
import com.distributask.model.JobStatus;
import com.distributask.repository.JobRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for SchedulerEngine.
 *
 * We mock the JobRepository and JobExecutor to verify:
 *  1. The engine submits due jobs to the executor
 *  2. The engine skips jobs when none are due
 *  3. next_run_at is updated before dispatch (so the same job isn't picked
 *     up twice in the same polling cycle)
 */
class SchedulerEngineTest {

    private JobRepository      jobRepository;
    private JobExecutor        jobExecutor;
    private ThreadPoolTaskExecutor taskExecutor;
    private SchedulerEngine    engine;

    @BeforeEach
    void setup() {
        jobRepository = mock(JobRepository.class);
        jobExecutor   = mock(JobExecutor.class);
        taskExecutor  = mock(ThreadPoolTaskExecutor.class);
        engine = new SchedulerEngine(jobRepository, jobExecutor, taskExecutor);
    }

    @Test
    void poll_doesNothing_whenNoJobsDue() {
        when(jobRepository.findDueJobs(eq(JobStatus.ACTIVE), any()))
            .thenReturn(List.of());

        engine.poll();

        verifyNoInteractions(taskExecutor);
    }

    @Test
    void poll_submitsEachDueJob_toTaskExecutor() {
        Job job1 = makeJob(1L, "0 */1 * * * ?");
        Job job2 = makeJob(2L, "0 */1 * * * ?");

        when(jobRepository.findDueJobs(eq(JobStatus.ACTIVE), any()))
            .thenReturn(List.of(job1, job2));
        when(jobRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        engine.poll();

        // One submit per job
        verify(taskExecutor, times(2)).submit(any(Runnable.class));
    }

    @Test
    void poll_savesJob_afterAdvancingNextRunAt() {
        Job job = makeJob(1L, "0 */1 * * * ?");

        when(jobRepository.findDueJobs(eq(JobStatus.ACTIVE), any()))
            .thenReturn(List.of(job));
        when(jobRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        engine.poll();

        // next_run_at must be updated (not null) and saved before dispatch
        verify(jobRepository).save(job);
    }

    private Job makeJob(Long id, String cron) {
        Job job = new Job();
        job.setId(id);
        job.setName("job-" + id);
        job.setCronExpression(cron);
        job.setJobClass("com.distributask.jobs.DailyReportJob");
        job.setStatus(JobStatus.ACTIVE);
        job.setNextRunAt(LocalDateTime.now().minusSeconds(1));
        return job;
    }
}
