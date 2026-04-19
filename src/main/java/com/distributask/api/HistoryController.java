package com.distributask.api;

import com.distributask.model.JobExecutionHistory;
import com.distributask.repository.JobHistoryRepository;
import com.distributask.repository.JobRepository;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * REST endpoint for querying a job's execution audit trail.
 *
 *   GET /api/jobs/{id}/history   — returns all runs newest-first
 */
@RestController
@RequestMapping("/api/jobs")
public class HistoryController {

    private final JobHistoryRepository historyRepository;
    private final JobRepository        jobRepository;

    public HistoryController(JobHistoryRepository historyRepository,
                             JobRepository jobRepository) {
        this.historyRepository = historyRepository;
        this.jobRepository     = jobRepository;
    }

    @GetMapping("/{id}/history")
    public List<JobExecutionHistory> getHistory(@PathVariable Long id) {
        // Validate that the job exists before returning history
        jobRepository.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Job not found"));

        return historyRepository.findByJobIdOrderByStartedAtDesc(id);
    }
}
