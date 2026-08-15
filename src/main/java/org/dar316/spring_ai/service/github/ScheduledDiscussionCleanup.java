package org.dar316.spring_ai.service.github;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class ScheduledDiscussionCleanup {
    private static final Logger log = LoggerFactory.getLogger(ScheduledDiscussionCleanup.class);
    private final VectorStore vectorStore;

    public ScheduledDiscussionCleanup(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    // Run at 03:00 AM every day
    @Scheduled(cron = "0 0 20 * * *")
    public void cleanupExpiredDiscussions() {
        log.info("Running scheduled cleanup for expired GitHub discussions...");

        try {
            var feb = new FilterExpressionBuilder();

            // Build the filter:
            // source_type = 'github' AND document_type = 'discussion' AND expires_at < current_time
            var filter = feb.and(
                    feb.eq("source_type", "github"),
                    feb.and(
                            feb.eq("document_type", "discussion"),
                            feb.lt("expires_at", System.currentTimeMillis())
                    )
            ).build();

            vectorStore.delete(filter);
            log.info("Expired discussion cleanup completed successfully.");
        } catch (Exception e) {
            log.error("Failed to execute expired discussion cleanup", e);
        }
    }
}
