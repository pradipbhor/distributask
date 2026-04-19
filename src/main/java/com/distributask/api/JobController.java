package com.distributask.api;

import com.distributask.model.Job;
import com.distributask.model.JobStatus;
import com.distributask.repository.JobRepository;
import org.quartz.CronExpression;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.text.ParseException;
import java.util.List;
import java.util.Map;

/**
 * REST API for job lifecycle management.
 *
 * All mutating operations validate the cron expression before persisting
 * so the scheduler never encounters a job with an unparseable schedule.
 *
 * Endpoints:
 *   POST   /api/jobs          — create a new job
 *   GET    /api/jobs          — list all non-deleted jobs
 *   GET    /api/jobs/{id}     — get a single job
 *   PUT    /api/jobs/{id}/pause   — pause
 *   PUT    /api/jobs/{id}/resume  — resume
 *   DELETE /api/jobs/{id}        — soft-delete
 */
@RestController
@RequestMapping("/api/jobs")
public class JobController {

    private final JobRepository jobRepository;

    public JobController(JobRepository jobRepository) {
        this.jobRepository = jobRepository;
    }

    // ── Create ────────────────────────────────────────────────────────────

    @PostMapping
    public ResponseEntity<Job> create(@RequestBody Map<String, Object> body) {
        Job job = new Job();
        job.setName(requireString(body, "name"));
        job.setJobClass(requireString(body, "jobClass"));

        String cron = requireString(body, "cronExpression");
        validateCron(cron);
        job.setCronExpression(cron);

        if (body.containsKey("maxRetries")) {
            job.setMaxRetries(Integer.parseInt(body.get("maxRetries").toString()));
        }

        return ResponseEntity.status(HttpStatus.CREATED).body(jobRepository.save(job));
    }

    // ── Read ──────────────────────────────────────────────────────────────

    @GetMapping
    public List<Job> listAll() {
        return jobRepository.findByStatusNot(JobStatus.DELETED);
    }

    @GetMapping("/{id}")
    public Job getById(@PathVariable Long id) {
        return jobRepository.findById(id)
            .filter(j -> j.getStatus() != JobStatus.DELETED)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Job not found"));
    }

    // ── Pause / Resume ────────────────────────────────────────────────────

    @PutMapping("/{id}/pause")
    public Job pause(@PathVariable Long id) {
        Job job = requireJob(id);
        if (job.getStatus() != JobStatus.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Only ACTIVE jobs can be paused; current status: " + job.getStatus());
        }
        job.setStatus(JobStatus.PAUSED);
        return jobRepository.save(job);
    }

    @PutMapping("/{id}/resume")
    public Job resume(@PathVariable Long id) {
        Job job = requireJob(id);
        if (job.getStatus() != JobStatus.PAUSED && job.getStatus() != JobStatus.FAILED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Only PAUSED or FAILED jobs can be resumed; current status: " + job.getStatus());
        }
        job.setStatus(JobStatus.ACTIVE);
        job.setRetryCount(0);
        // null next_run_at causes the scheduler to pick it up immediately
        job.setNextRunAt(null);
        return jobRepository.save(job);
    }

    // ── Delete (soft) ────────────────────────────────────────────────────

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        Job job = requireJob(id);
        job.setStatus(JobStatus.DELETED);
        jobRepository.save(job);
        return ResponseEntity.noContent().build();
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private Job requireJob(Long id) {
        return jobRepository.findById(id)
            .filter(j -> j.getStatus() != JobStatus.DELETED)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Job not found"));
    }

    private static String requireString(Map<String, Object> body, String key) {
        Object v = body.get(key);
        if (v == null || v.toString().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing field: " + key);
        }
        return v.toString().trim();
    }

    private static void validateCron(String expr) {
        try {
            new CronExpression(expr);
        } catch (ParseException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Invalid cron expression '" + expr + "': " + e.getMessage());
        }
    }
}
