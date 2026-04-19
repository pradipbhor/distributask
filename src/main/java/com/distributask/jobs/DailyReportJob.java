package com.distributask.jobs;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Example job: simulates generating and emailing a daily report.
 *
 * Register via API:
 *   POST /api/jobs
 *   { "name": "daily-report", "cronExpression": "0 0 9 * * ?",
 *     "jobClass": "com.distributask.jobs.DailyReportJob", "maxRetries": 3 }
 *
 * In a real implementation this would fetch data, generate a PDF, and send
 * it via JavaMailSender or an email API. Throwing RuntimeException here
 * lets you test the retry + exponential backoff logic easily.
 */
@Component
public class DailyReportJob implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(DailyReportJob.class);

    @Override
    public void run() {
        log.info("DailyReportJob: generating daily report...");
        // Simulate work
        try { Thread.sleep(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        log.info("DailyReportJob: report generated and sent");
    }
}
