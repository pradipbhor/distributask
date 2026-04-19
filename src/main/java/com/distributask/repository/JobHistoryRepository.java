package com.distributask.repository;

import com.distributask.model.JobExecutionHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface JobHistoryRepository extends JpaRepository<JobExecutionHistory, Long> {

    /** Returns execution history newest-first for the given job. */
    List<JobExecutionHistory> findByJobIdOrderByStartedAtDesc(Long jobId);
}
