# DistribuTask — Distributed Job Scheduler

A production-grade distributed job scheduler built with **Java 17 + Spring Boot 3**.
Runs the same application on multiple servers simultaneously and guarantees that every scheduled job executes **exactly once** — no duplicates, no missed runs.

> **Core problem solved:** When 3 servers all wake up at 9 AM and see the same job is due, how do you make sure only one of them runs it?  
> **Answer:** Redis SETNX distributed lock — atomic, self-healing, zero infrastructure overhead.

---

## Table of Contents

1. [What This Project Does](#1-what-this-project-does)
2. [Architecture — How All the Pieces Connect](#2-architecture--how-all-the-pieces-connect)
3. [Tech Stack — What Each Tool Is Used For](#3-tech-stack--what-each-tool-is-used-for)
4. [Project Structure — Every File Explained](#4-project-structure--every-file-explained)
5. [Code Walkthrough — Logic Bit by Bit](#5-code-walkthrough--logic-bit-by-bit)
   - [5.1 JobStatus Enum](#51-jobstatus-enum)
   - [5.2 Job Entity](#52-job-entity)
   - [5.3 JobExecutionHistory Entity](#53-jobexecutionhistory-entity)
   - [5.4 Repositories](#54-repositories)
   - [5.5 Redis Distributed Lock](#55-redis-distributed-lock)
   - [5.6 Token Bucket Rate Limiter](#56-token-bucket-rate-limiter)
   - [5.7 RetryHandler — Exponential Backoff](#57-retryhandler--exponential-backoff)
   - [5.8 JobExecutor — Execution Lifecycle](#58-jobexecutor--execution-lifecycle)
   - [5.9 SchedulerEngine — Polling Loop](#59-schedulerengine--polling-loop)
   - [5.10 REST API Controllers](#510-rest-api-controllers)
6. [Database Schema](#6-database-schema)
7. [How to Run](#7-how-to-run)
   - [Option A: Docker Compose (recommended)](#option-a-docker-compose-recommended)
   - [Option B: Local Development](#option-b-local-development)
8. [How to Use the API — Step by Step](#8-how-to-use-the-api--step-by-step)
9. [How to Create Your Own Job](#9-how-to-create-your-own-job)
10. [Cron Expression Cheat Sheet](#10-cron-expression-cheat-sheet)
11. [Observability — Metrics and Dashboards](#11-observability--metrics-and-dashboards)
12. [Configuration Reference](#12-configuration-reference)
13. [Running Tests](#13-running-tests)
14. [Common Questions Answered](#14-common-questions-answered)

---

## 1. What This Project Does

You have a job (e.g. "send a daily report at 9 AM"). You also have 3 servers all running your application. Without coordination, all 3 servers will try to send that report at 9 AM — 3 duplicate emails, 3 database writes, broken downstream systems.

DistribuTask solves this by:

| Problem | Solution |
|---------|----------|
| Duplicate execution across servers | Redis SETNX lock — only 1 server wins |
| Job lost when a server crashes mid-run | TTL on Redis key — lock auto-releases |
| Flaky jobs failing permanently | Exponential backoff retry (20s → 40s → 80s) |
| Server overloaded with too many parallel jobs | Token bucket rate limiter per server |
| No visibility into what ran and when | Full execution history in MySQL |
| Manual job management | REST API to create, pause, resume, delete |

---

## 2. Architecture — How All the Pieces Connect

```
┌─────────────────────────────────────────────────────────────────┐
│                        REST API Layer                            │
│   POST /api/jobs   GET /api/jobs   PUT /api/jobs/1/pause  ...   │
│   JobController                    HistoryController            │
└─────────────────────────┬───────────────────────────────────────┘
                          │  reads/writes
                          ▼
┌─────────────────────────────────────────────────────────────────┐
│                     MySQL Database                               │
│   jobs table              job_execution_history table           │
│   - what to run           - audit trail of every run            │
│   - when to run           - which worker ran it                  │
│   - status/retries        - duration, errors                    │
└─────────────────────────┬───────────────────────────────────────┘
                          │  findDueJobs() every 5s
                          ▼
┌─────────────────────────────────────────────────────────────────┐
│              SchedulerEngine  (@Scheduled every 5s)             │
│   1. Query MySQL for ACTIVE jobs where next_run_at <= NOW()     │
│   2. Advance next_run_at using cron expression                  │
│   3. Submit each job to thread pool                             │
└────────────┬────────────────────────────────────────────────────┘
             │  submit to thread pool
             ▼
┌─────────────────────────────────────────────────────────────────┐
│                       JobExecutor                                │
│   1. TokenBucketLimiter.tryAcquire()  — skip if overloaded     │
│   2. RedisDistributedLock.acquireLock() — skip if other won    │
│   3. Write RUNNING history row                                   │
│   4. Resolve Runnable bean from Spring context                  │
│   5. Run it (timed)                                             │
│   6. Update history + RetryHandler + release lock              │
└────────────┬────────────────────────────────────────────────────┘
             │                          │
     ┌───────▼────────┐      ┌──────────▼─────────┐
     │     Redis       │      │    MySQL            │
     │  job:lock:42   │      │  execution_history  │
     │  (SETNX + TTL) │      │  job status update  │
     └────────────────┘      └─────────────────────┘

═══════════════════════════════════════════════════════════════════
MULTI-NODE VIEW: All 3 workers run identical code simultaneously
═══════════════════════════════════════════════════════════════════

  worker-1 ──── poll MySQL ──┐
  worker-2 ──── poll MySQL ──┼──► same due jobs found
  worker-3 ──── poll MySQL ──┘

  worker-1 ──── SETNX job:lock:42 ──► SUCCESS  ──► executes job
  worker-2 ──── SETNX job:lock:42 ──► FAIL     ──► skips
  worker-3 ──── SETNX job:lock:42 ──► FAIL     ──► skips
```

---

## 3. Tech Stack — What Each Tool Is Used For

| Technology | Why It's Used |
|------------|--------------|
| **Java 17** | Language — records, sealed classes, modern switch |
| **Spring Boot 3** | Web server, dependency injection, `@Scheduled`, JPA |
| **MySQL 8** | Persistent store for job definitions and execution history |
| **Redis 7** | Distributed lock (SETNX) and token bucket rate limiting |
| **Quartz** | Cron expression parser — computes "next fire time" |
| **Micrometer + Prometheus** | Metrics collection exposed at `/actuator/prometheus` |
| **Grafana** | Dashboard to visualise metrics |
| **Docker Compose** | Runs 3 workers + MySQL + Redis + Prometheus + Grafana with one command |
| **JUnit 5 + Mockito** | Unit tests — no real Redis or MySQL needed |

---

## 4. Project Structure — Every File Explained

```
SecureVault/
├── pom.xml                                          # Maven dependencies
├── Dockerfile                                       # 2-stage build (JDK builder → JRE runtime)
├── docker-compose.yml                               # 3 workers + MySQL + Redis + monitoring
├── prometheus.yml                                   # tells Prometheus where to scrape metrics
│
└── src/
    ├── main/
    │   ├── java/com/distributask/
    │   │   │
    │   │   ├── DistribuTaskApplication.java         # Spring Boot entry point
    │   │   │
    │   │   ├── model/
    │   │   │   ├── JobStatus.java                   # enum: ACTIVE PAUSED FAILED DELETED
    │   │   │   ├── Job.java                         # @Entity — jobs table
    │   │   │   └── JobExecutionHistory.java         # @Entity — job_execution_history table
    │   │   │
    │   │   ├── repository/
    │   │   │   ├── JobRepository.java               # JPA repo + findDueJobs() query
    │   │   │   └── JobHistoryRepository.java        # JPA repo — history by job id
    │   │   │
    │   │   ├── lock/
    │   │   │   └── RedisDistributedLock.java        # SETNX acquire/release + Prometheus counters
    │   │   │
    │   │   ├── ratelimit/
    │   │   │   └── TokenBucketLimiter.java          # Semaphore-based per-node rate limit
    │   │   │
    │   │   ├── engine/
    │   │   │   ├── RetryHandler.java                # exponential backoff + FAILED state logic
    │   │   │   ├── JobExecutor.java                 # lock → run → history → retry (full lifecycle)
    │   │   │   └── SchedulerEngine.java             # @Scheduled poll every 5s → submit to pool
    │   │   │
    │   │   ├── api/
    │   │   │   ├── JobController.java               # POST/GET/PUT/DELETE /api/jobs
    │   │   │   └── HistoryController.java           # GET /api/jobs/{id}/history
    │   │   │
    │   │   ├── config/
    │   │   │   └── SchedulerConfig.java             # ThreadPoolTaskExecutor + @EnableScheduling
    │   │   │
    │   │   └── jobs/
    │   │       ├── DailyReportJob.java              # example Runnable (register via API)
    │   │       └── CleanupJob.java                  # example Runnable
    │   │
    │   └── resources/
    │       ├── application.yml                      # default config (reads env vars for Docker)
    │       └── application-local.yml                # overrides for local dev without Docker
    │
    └── test/java/com/distributask/
        ├── RedisLockTest.java                       # 5 tests: acquire/release behaviour
        ├── RetryHandlerTest.java                    # 4 tests: delay formula, FAILED state
        └── SchedulerEngineTest.java                 # 3 tests: polling + dispatch behaviour
```

---

## 5. Code Walkthrough — Logic Bit by Bit

### 5.1 JobStatus Enum

**File:** `model/JobStatus.java`

```java
public enum JobStatus {
    ACTIVE,   // scheduler will poll and run this job
    PAUSED,   // scheduler ignores it; operator chose to stop it temporarily
    FAILED,   // all retries exhausted — needs manual intervention
    DELETED   // soft-deleted; hidden from API but history kept
}
```

**Why soft-delete?** Deleting a job row would orphan its execution history rows. By setting `status = DELETED` we keep the full audit trail but hide it from normal listings.

---

### 5.2 Job Entity

**File:** `model/Job.java`

```java
@Entity
@Table(name = "jobs")
public class Job {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;              // human-readable label
    private String cronExpression;    // "0 0 9 * * ?" = 9am daily
    private String jobClass;          // "com.distributask.jobs.DailyReportJob"

    @Enumerated(EnumType.STRING)
    private JobStatus status = JobStatus.ACTIVE;

    private LocalDateTime nextRunAt;      // pre-computed next fire time
    private LocalDateTime lastRunAt;      // when it last ran
    private String        lastRunStatus;  // "SUCCESS" or "FAILED"
    private int           maxRetries = 3;
    private int           retryCount = 0; // resets to 0 on success
    private LocalDateTime createdAt = LocalDateTime.now();
}
```

**Key design — `nextRunAt`:** The scheduler pre-computes and persists the next fire time *before* dispatching the job. This means even if the same polling cycle runs on all 3 workers, they all see the same `nextRunAt` and the Redis lock resolves the race. It also means restarting a worker won't cause it to re-run an already-executed job.

---

### 5.3 JobExecutionHistory Entity

**File:** `model/JobExecutionHistory.java`

```java
@Entity
@Table(name = "job_execution_history")
public class JobExecutionHistory {
    private Long          id;
    private Long          jobId;
    private String        workerNode;    // "worker-1", "worker-2", "worker-3"
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private Long          durationMs;
    private String        status;        // "RUNNING", "SUCCESS", "FAILED"
    private String        errorMessage;  // stack trace excerpt on failure
}
```

**Why write a RUNNING row first?** If a worker crashes mid-execution, the row stays as `status = RUNNING` forever — an operator can look for stale RUNNING rows (older than the lock TTL) to detect crashed jobs. Without this, a crash would leave no trace.

---

### 5.4 Repositories

**File:** `repository/JobRepository.java`

```java
// The most important query in the whole system:
@Query("SELECT j FROM Job j WHERE j.status = :status AND (j.nextRunAt IS NULL OR j.nextRunAt <= :now)")
List<Job> findDueJobs(@Param("status") JobStatus status, @Param("now") LocalDateTime now);

// For GET /api/jobs — hides DELETED jobs
List<Job> findByStatusNot(JobStatus status);
```

The `nextRunAt IS NULL` condition handles newly-created jobs that have never run — they have no computed next time yet, so we treat them as immediately due.

**File:** `repository/JobHistoryRepository.java`

```java
// Returns history newest-first for GET /api/jobs/{id}/history
List<JobExecutionHistory> findByJobIdOrderByStartedAtDesc(Long jobId);
```

---

### 5.5 Redis Distributed Lock

**File:** `lock/RedisDistributedLock.java`

This is the mechanism that makes exactly-once execution possible.

```java
// Redis command issued under the hood:
// SET job:lock:42 "worker-1" NX PX 300000
//                              ^^  ^^^
//                              NX = only set if key does NOT exist
//                              PX = expire after 300,000 ms (5 minutes)

public boolean acquireLock(Long jobId, int ttlSeconds) {
    Boolean acquired = redis.opsForValue()
        .setIfAbsent(
            "job:lock:" + jobId,  // key:   "job:lock:42"
            workerNode,           // value: "worker-1"
            Duration.ofSeconds(ttlSeconds)
        );
    return Boolean.TRUE.equals(acquired);
}
```

**What "NX" (setIfAbsent) means:** Redis processes commands one at a time. Even if 3 workers call this simultaneously, Redis serialises them internally:
- Worker-1: key doesn't exist → SET succeeds → returns `true`
- Worker-2: key now exists → SET fails → returns `false`
- Worker-3: key now exists → SET fails → returns `false`

Only worker-1 runs the job. This is guaranteed by Redis's single-threaded command processing.

```java
public void releaseLock(Long jobId) {
    String key = "job:lock:" + jobId;
    String currentOwner = redis.opsForValue().get(key);

    if (workerNode.equals(currentOwner)) {
        redis.delete(key);  // only delete if WE still own it
    }
    // If currentOwner != us, the TTL already expired and another
    // worker re-acquired it — we must NOT delete their lock.
}
```

**Timeline with a crash:**
```
t=0s    worker-1 acquires lock (TTL=300s)
t=10s   worker-1 crashes (JVM killed, Docker container killed, etc.)
t=300s  Redis TTL expires — key "job:lock:42" automatically deleted
t=305s  Next poll cycle — any available worker can now acquire the lock
```
No human intervention needed. No deadlock ever.

---

### 5.6 Token Bucket Rate Limiter

**File:** `ratelimit/TokenBucketLimiter.java`

```java
// JVM Semaphore with N permits = N tokens in the bucket
private final Semaphore semaphore = new Semaphore(maxConcurrentJobs);

public boolean tryAcquire() {
    return semaphore.tryAcquire(); // non-blocking: returns false immediately if full
}

public void release() {
    semaphore.release(); // always called in finally block
}
```

**Why this matters:** Imagine 50 jobs all become due at midnight (e.g. end-of-day batch jobs). Without rate limiting, the scheduler would submit 50 jobs to the thread pool simultaneously, potentially:
- Exhausting database connections
- Overwhelming downstream APIs
- Causing OOM errors

With the token bucket (default: 10 concurrent jobs), at most 10 run simultaneously. The other 40 are picked up in the next 5-second poll cycle when tokens are released.

**Per-node, not global:** Each worker independently limits its own concurrency. To enforce a cluster-wide limit, you'd store the counter in Redis — not needed for this scale.

---

### 5.7 RetryHandler — Exponential Backoff

**File:** `engine/RetryHandler.java`

```java
public void handleFailure(Job job, Exception e) {
    job.setRetryCount(job.getRetryCount() + 1);

    if (job.getRetryCount() <= job.getMaxRetries()) {
        // Formula: 2^retryCount × 10 seconds
        long delaySeconds = (long) Math.pow(2, job.getRetryCount()) * 10;
        job.setNextRunAt(LocalDateTime.now().plusSeconds(delaySeconds));

        // Job stays ACTIVE — scheduler will pick it up after the delay
    } else {
        // All retries exhausted — stop scheduling
        job.setStatus(JobStatus.FAILED);
    }
}

public void handleSuccess(Job job) {
    job.setRetryCount(0);          // reset so future failures start fresh
    job.setLastRunStatus("SUCCESS");
}
```

**Delay table:**

| Retry attempt | Formula | Delay |
|--------------|---------|-------|
| 1st | 2¹ × 10 | **20 seconds** |
| 2nd | 2² × 10 | **40 seconds** |
| 3rd | 2³ × 10 | **80 seconds** |
| Beyond maxRetries | — | **Job → FAILED** |

**Why exponential, not fixed?** If your job calls an external API and that API goes down, hammering it every 20 seconds makes recovery harder. Exponential backoff gives the dependency time to recover. The same pattern is used in AWS SDK, Kafka consumer retry, and Spring Retry.

---

### 5.8 JobExecutor — Execution Lifecycle

**File:** `engine/JobExecutor.java`

This is the most complex class — it orchestrates everything for a single job execution:

```
execute(job)
    │
    ├─ 1. TokenBucketLimiter.tryAcquire()
    │       └─ false → log "rate limit hit" → return (skip this cycle)
    │
    ├─ 2. RedisDistributedLock.acquireLock(job.getId(), 300)
    │       └─ false → log "already claimed" → release token → return
    │
    ├─ 3. Write history row with status="RUNNING"
    │       (crash-safe: if we die here, row stays RUNNING — detectable)
    │
    ├─ 4. resolveJobBean(job.getJobClass())
    │       └─ Class.forName("com.distributask.jobs.DailyReportJob")
    │          applicationContext.getBean(DailyReportJob.class)
    │          (Spring injects @Autowired fields into the job)
    │
    ├─ 5. jobTimer.record(jobBean::run)
    │       └─ runs the job; duration recorded for Prometheus
    │
    ├─ SUCCESS path:
    │   ├─ update history: status="SUCCESS", completedAt, durationMs
    │   ├─ retryHandler.handleSuccess(job) → retryCount = 0
    │   ├─ job.lastRunAt = startTime; save to MySQL
    │   └─ distributedLock.releaseLock(job.getId())
    │
    └─ FAILURE path:
        ├─ update history: status="FAILED", errorMessage
        ├─ retryHandler.handleFailure(job, e) → compute nextRunAt or → FAILED
        ├─ job.lastRunAt = startTime; save to MySQL
        └─ distributedLock.releaseLock(job.getId())  ← always in finally
```

**Why resolve beans from ApplicationContext instead of `new DailyReportJob()`?**  
If your job has `@Autowired` dependencies (e.g. a `UserRepository`, an email service), using `new` bypasses Spring's injection — those fields would be null. Using `applicationContext.getBean()` lets Spring fully wire the job class.

---

### 5.9 SchedulerEngine — Polling Loop

**File:** `engine/SchedulerEngine.java`

```java
@Scheduled(fixedDelayString = "${scheduler.poll-interval-ms:5000}")
public void poll() {
    // Step 1: Find all due jobs
    List<Job> dueJobs = jobRepository.findDueJobs(JobStatus.ACTIVE, LocalDateTime.now());

    for (Job job : dueJobs) {
        // Step 2: Advance next_run_at BEFORE dispatching
        // This prevents the next poll (5s later) from seeing the same job as due
        advanceNextRunAt(job);      // uses Quartz CronExpression parser
        jobRepository.save(job);   // persist the new nextRunAt

        // Step 3: Submit to thread pool (non-blocking)
        taskExecutor.submit(() -> jobExecutor.execute(job));
    }
}

private void advanceNextRunAt(Job job) {
    CronExpression cron = new CronExpression(job.getCronExpression());
    Date nextFire = cron.getNextValidTimeAfter(new Date());
    // converts Date → LocalDateTime and sets job.nextRunAt
}
```

**Why `fixedDelay` instead of `fixedRate`?**  
- `fixedRate`: fires every 5s regardless of how long the poll takes. If a poll takes 6s, you get overlapping polls.
- `fixedDelay`: waits 5s *after* the poll finishes. Safer — no overlapping polls under load.

**Why advance `nextRunAt` before dispatching?**  
The job dispatch to the thread pool is asynchronous. If we advanced `nextRunAt` inside `JobExecutor` instead, the next poll (5s later) could still see the old `nextRunAt` and try to re-dispatch the same job — creating a race condition with the Redis lock.

---

### 5.10 REST API Controllers

**File:** `api/JobController.java`

```
POST   /api/jobs              → create a job (validates cron before saving)
GET    /api/jobs              → list all non-deleted jobs
GET    /api/jobs/{id}         → get one job
PUT    /api/jobs/{id}/pause   → ACTIVE → PAUSED
PUT    /api/jobs/{id}/resume  → PAUSED or FAILED → ACTIVE (clears retryCount)
DELETE /api/jobs/{id}         → ACTIVE/PAUSED → DELETED (soft delete)
```

**Cron validation on create:**
```java
// Before saving, we parse the cron with Quartz to catch typos:
try {
    new CronExpression(expr);
} catch (ParseException e) {
    throw new ResponseStatusException(400, "Invalid cron expression: " + e.getMessage());
}
```

**Resume also handles FAILED jobs:**  
A job stuck in FAILED state (all retries exhausted) can be revived via resume — it resets `retryCount = 0`, sets `status = ACTIVE`, and sets `nextRunAt = null` so it runs on the very next poll cycle.

**File:** `api/HistoryController.java`

```
GET /api/jobs/{id}/history  → returns execution history newest-first
```

---

## 6. Database Schema

```sql
-- Stores what jobs exist and their current scheduling state
CREATE TABLE jobs (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    name             VARCHAR(255) NOT NULL,
    cron_expression  VARCHAR(100) NOT NULL,
    job_class        VARCHAR(500) NOT NULL,         -- fully qualified class name
    status           ENUM('ACTIVE','PAUSED','FAILED','DELETED') DEFAULT 'ACTIVE',
    next_run_at      DATETIME,                      -- NULL = run on next poll
    last_run_at      DATETIME,
    last_run_status  VARCHAR(20),                   -- 'SUCCESS' or 'FAILED'
    max_retries      INT DEFAULT 3,
    retry_count      INT DEFAULT 0,
    created_at       DATETIME DEFAULT CURRENT_TIMESTAMP
);

-- Immutable audit trail — one row per execution attempt
CREATE TABLE job_execution_history (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    job_id        BIGINT NOT NULL,
    worker_node   VARCHAR(100),                     -- which server ran this
    started_at    DATETIME,
    completed_at  DATETIME,
    duration_ms   BIGINT,
    status        VARCHAR(20),                      -- 'RUNNING', 'SUCCESS', 'FAILED'
    error_message TEXT,                             -- exception message on failure
    FOREIGN KEY (job_id) REFERENCES jobs(id)
);
```

> **Note:** Spring Boot with `spring.jpa.hibernate.ddl-auto=update` creates these tables automatically on first startup. No manual SQL needed.

---

## 7. How to Run

### Option A: Docker Compose (recommended)

**Prerequisites:** Docker Desktop installed and running.

```bash
cd /Users/pradipbhor/work/SecureVault
docker compose up --build
```

This builds the JAR inside Docker and starts:

| Service | Port | What it is |
|---------|------|-----------|
| MySQL | 3306 | job storage |
| Redis | 6379 | distributed locks |
| worker-1 | **8081** | Spring Boot app, node 1 |
| worker-2 | **8082** | Spring Boot app, node 2 |
| worker-3 | **8083** | Spring Boot app, node 3 |
| Prometheus | 9090 | metrics scraping |
| Grafana | 3000 | dashboards (admin/admin) |

Wait ~30 seconds for MySQL to become healthy. Watch the logs:
```bash
docker compose logs -f worker-1 worker-2 worker-3
```

**Stop everything:**
```bash
docker compose down          # keep MySQL data
docker compose down -v       # also delete MySQL data volume
```

---

### Option B: Local Development

**Prerequisites:** Java 17+, and Docker for MySQL/Redis.

```bash
# Step 1: start only the infrastructure
docker compose up mysql redis -d

# Step 2: run the app with the local profile
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

The `local` profile connects to `localhost:3306` and `localhost:6379`, and sets poll interval to 2s for faster feedback.

---

## 8. How to Use the API — Step by Step

### Step 1 — Create a job

```bash
curl -X POST http://localhost:8081/api/jobs \
  -H "Content-Type: application/json" \
  -d '{
    "name": "daily-report",
    "cronExpression": "0 */1 * * * ?",
    "jobClass": "com.distributask.jobs.DailyReportJob",
    "maxRetries": 3
  }'
```

**Response:**
```json
{
  "id": 1,
  "name": "daily-report",
  "cronExpression": "0 */1 * * * ?",
  "jobClass": "com.distributask.jobs.DailyReportJob",
  "status": "ACTIVE",
  "nextRunAt": null,
  "lastRunAt": null,
  "lastRunStatus": null,
  "maxRetries": 3,
  "retryCount": 0,
  "createdAt": "2026-04-20T10:00:00"
}
```

`nextRunAt: null` means it will fire on the very next poll cycle (within 5 seconds).

---

### Step 2 — List all jobs

```bash
curl http://localhost:8081/api/jobs
```

```json
[
  {
    "id": 1,
    "name": "daily-report",
    "status": "ACTIVE",
    "nextRunAt": "2026-04-20T10:01:00",
    "lastRunAt": "2026-04-20T10:00:01",
    "lastRunStatus": "SUCCESS",
    "retryCount": 0
  }
]
```

---

### Step 3 — Get one job

```bash
curl http://localhost:8081/api/jobs/1
```

---

### Step 4 — See execution history (which worker ran it, how long it took)

```bash
curl http://localhost:8081/api/jobs/1/history
```

```json
[
  {
    "id": 3,
    "jobId": 1,
    "workerNode": "worker-2",
    "startedAt": "2026-04-20T10:01:00",
    "completedAt": "2026-04-20T10:01:00.502",
    "durationMs": 502,
    "status": "SUCCESS",
    "errorMessage": null
  },
  {
    "id": 2,
    "jobId": 1,
    "workerNode": "worker-1",
    "startedAt": "2026-04-20T10:00:01",
    "completedAt": "2026-04-20T10:00:01.511",
    "durationMs": 511,
    "status": "SUCCESS",
    "errorMessage": null
  }
]
```

The `workerNode` field proves that different workers ran different executions — and that no execution was duplicated.

---

### Step 5 — Pause a job

```bash
curl -X PUT http://localhost:8081/api/jobs/1/pause
```

The job's status moves to `PAUSED`. The scheduler ignores PAUSED jobs — no more executions until resumed.

---

### Step 6 — Resume a job

```bash
curl -X PUT http://localhost:8081/api/jobs/1/resume
```

Status moves back to `ACTIVE`. The job fires on the next poll cycle.

---

### Step 7 — Delete a job (soft delete)

```bash
curl -X DELETE http://localhost:8081/api/jobs/1
```

Status moves to `DELETED`. The job disappears from `GET /api/jobs` but its execution history is preserved in the database.

---

### Step 8 — Observe distributed locking in real time

```bash
# Watch all 3 workers simultaneously
docker compose logs -f worker-1 worker-2 worker-3
```

You will see output like:
```
worker-1  | Lock acquired: key=job:lock:1 owner=worker-1
worker-1  | DailyReportJob: generating daily report...
worker-1  | Job 1 'daily-report' completed in 502ms on worker-1
worker-2  | Lock NOT acquired: key=job:lock:1 already held by another worker
worker-3  | Lock NOT acquired: key=job:lock:1 already held by another worker
```

---

## 9. How to Create Your Own Job

**Step 1:** Create a class in `src/main/java/com/distributask/jobs/` that implements `Runnable` and is annotated with `@Component`:

```java
package com.distributask.jobs;

import org.springframework.stereotype.Component;

@Component
public class SendInvoiceJob implements Runnable {

    private final InvoiceService invoiceService;  // injected by Spring

    public SendInvoiceJob(InvoiceService invoiceService) {
        this.invoiceService = invoiceService;
    }

    @Override
    public void run() {
        invoiceService.sendPendingInvoices();
    }
}
```

**Step 2:** Rebuild and register via API:

```bash
# Rebuild (if running locally)
./mvnw package -DskipTests

# Register the job (fires every day at 2am)
curl -X POST http://localhost:8081/api/jobs \
  -H "Content-Type: application/json" \
  -d '{
    "name": "send-invoices",
    "cronExpression": "0 0 2 * * ?",
    "jobClass": "com.distributask.jobs.SendInvoiceJob",
    "maxRetries": 5
  }'
```

**Step 3 (Docker):** Rebuild the Docker image:

```bash
docker compose up --build worker-1 worker-2 worker-3
```

---

## 10. Cron Expression Cheat Sheet

DistribuTask uses **Quartz 6-field cron format**: `seconds minutes hours day-of-month month day-of-week`

| Expression | Meaning |
|-----------|---------|
| `0 */1 * * * ?` | Every minute (good for testing) |
| `0 */5 * * * ?` | Every 5 minutes |
| `0 0 * * * ?` | Every hour on the hour |
| `0 0 9 * * ?` | Every day at 9:00 AM |
| `0 0 9 * * MON-FRI` | Weekdays at 9:00 AM |
| `0 0 2 * * ?` | Every day at 2:00 AM |
| `0 0 0 1 * ?` | First day of every month at midnight |
| `0 0 0 ? * SUN` | Every Sunday at midnight |
| `0 30 8 * * ?` | Every day at 8:30 AM |

> **Note:** Use `?` for day-of-week when you specify day-of-month (and vice versa). Quartz requires one of them to be `?`.

---

## 11. Observability — Metrics and Dashboards

**Raw metrics** (Prometheus format):
```bash
curl http://localhost:8081/actuator/prometheus | grep scheduler
```

| Metric | What it tells you |
|--------|------------------|
| `scheduler_jobs_executed_total` | Total successful job executions across all workers |
| `scheduler_job_duration_seconds` | Histogram of how long jobs take (p50, p95, p99) |
| `scheduler_lock_acquired_total` | How many times any worker acquired a lock |
| `scheduler_lock_failed_total` | How many times a worker lost the lock race (expected — shows multi-node is working) |
| `scheduler_retry_total` | Total retry attempts — high value = jobs are failing frequently |

**Prometheus** — open `http://localhost:9090`  
Example query: `rate(scheduler_jobs_executed_total[5m])` shows jobs/second over last 5 minutes.

**Grafana** — open `http://localhost:3000` (login: admin / admin)  
1. Click "Add data source" → Prometheus → URL: `http://prometheus:9090`
2. Create a dashboard, add panels using the metrics above

---

## 12. Configuration Reference

All settings are in `src/main/resources/application.yml` and can be overridden via environment variables:

| Property | Env var | Default | Meaning |
|----------|---------|---------|---------|
| `scheduler.poll-interval-ms` | — | `5000` | How often to query MySQL for due jobs |
| `scheduler.thread-pool.core-size` | — | `5` | Always-warm threads in the pool |
| `scheduler.thread-pool.max-size` | — | `20` | Maximum threads under peak load |
| `scheduler.rate-limit.max-concurrent-jobs` | — | `10` | Token bucket capacity per worker |
| `spring.datasource.url` | `MYSQL_HOST`, `MYSQL_DB` | `localhost:3306` | MySQL connection |
| `spring.data.redis.host` | `REDIS_HOST` | `localhost` | Redis host |
| `WORKER_NODE` | `WORKER_NODE` | hostname | Worker name shown in execution history |
| `server.port` | `SERVER_PORT` | `8080` | HTTP port |

---

## 13. Running Tests

```bash
./mvnw test
```

**Test files and what they cover:**

**`RedisLockTest.java`** — 5 tests, no real Redis (uses Mockito):
- `acquireLock` returns `true` when the key doesn't exist
- `acquireLock` returns `false` when another worker holds the key
- `acquireLock` handles `null` Redis response safely
- `releaseLock` deletes the key when we are the owner
- `releaseLock` does NOT delete when another worker re-acquired after our TTL expired

**`RetryHandlerTest.java`** — 4 tests:
- 1st failure sets `retryCount=1` and `nextRunAt = now + 20s`
- 2nd failure doubles the delay to 40s
- Failure beyond `maxRetries` moves status to `FAILED`
- Success resets `retryCount` to 0

**`SchedulerEngineTest.java`** — 3 tests (Mockito mocks repository + executor):
- Empty due-jobs list → `taskExecutor.submit` never called
- 2 due jobs → `taskExecutor.submit` called exactly 2 times
- `jobRepository.save` called before `submit` (next_run_at advanced first)

---

## 14. Common Questions Answered

**Q: What happens if all 3 workers crash at the same time?**  
MySQL still has the job definition. When any worker restarts, it will poll MySQL and find the job. If the job was mid-execution, the Redis lock TTL (5 minutes) will have expired, so the restarted worker can acquire it and retry.

**Q: What if a job runs longer than the Redis lock TTL (5 minutes)?**  
The lock expires and another worker could theoretically acquire it and start a second execution. To avoid this: (1) increase `LOCK_TTL_SECONDS` in `JobExecutor.java` for long-running jobs, or (2) implement a lock heartbeat (periodically refresh the TTL while the job is running).

**Q: What if MySQL is down?**  
The scheduler's poll will throw an exception. Spring logs the error and retries on the next poll cycle. Jobs already submitted to the thread pool will complete normally (they save history back to MySQL — those saves will also fail and log errors).

**Q: Can I add more worker nodes?**  
Yes. Add more workers to `docker-compose.yml` (copy worker-3, change ports and `WORKER_NODE`). They all share the same MySQL and Redis. The lock guarantees exactly-once execution regardless of how many workers you add.

**Q: How do I change the poll interval?**  
Set `scheduler.poll-interval-ms` in `application.yml` or pass it as a JVM arg:  
`./mvnw spring-boot:run -Dscheduler.poll-interval-ms=1000`

**Q: What if I register a jobClass that doesn't exist?**  
`JobExecutor.resolveJobBean()` catches `ClassNotFoundException` and throws `IllegalArgumentException`. The execution history row will show `status=FAILED` with the error message. The job will retry with exponential backoff.

**Q: Is there a UI?**  
No built-in UI — use the REST API or Grafana for monitoring. You can add a frontend later; the API is fully documented above.

**Q: How is this different from Spring's built-in `@Scheduled`?**  
`@Scheduled` runs on every node. If you have 3 servers, every `@Scheduled` method runs 3 times. DistribuTask's Redis lock ensures it runs exactly once. It also adds persistence (jobs survive restarts), retry logic, execution history, and a management API — none of which `@Scheduled` provides.

