package com.distributask.repository;

import com.distributask.model.Job;
import com.distributask.model.JobStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface JobRepository extends JpaRepository<Job, Long> {

    /**
     * Core polling query: finds all ACTIVE jobs whose scheduled fire time
     * has arrived. The scheduler engine calls this every 5 seconds.
     *
     * "next_run_at IS NULL" catches newly created jobs that haven't
     * computed their first fire time yet — the engine will compute it
     * and persist it before the next poll.
     */
    @Query("SELECT j FROM Job j WHERE j.status = :status AND (j.nextRunAt IS NULL OR j.nextRunAt <= :now)")
    List<Job> findDueJobs(@Param("status") JobStatus status, @Param("now") LocalDateTime now);

    /** For listing all non-deleted jobs via the GET /api/jobs endpoint. */
    List<Job> findByStatusNot(JobStatus status);
}
