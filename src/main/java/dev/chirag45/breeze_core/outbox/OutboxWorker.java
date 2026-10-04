package dev.chirag45.breeze_core.outbox;

import com.github.f4b6a3.uuid.UuidCreator;
import dev.chirag45.breeze_core.dto.translation.ProfileSnapshot;
import dev.chirag45.breeze_core.repository.UserRepository;
import dev.chirag45.breeze_core.temporal.ProjectInitializationStarter;
import com.clerk.backend_api.models.errors.ClerkError;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.bulkhead.ThreadPoolBulkhead;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class OutboxWorker {

    private static final Logger log = LoggerFactory.getLogger(OutboxWorker.class);

    private final OutboxStore store;
    private final UserRepository users;
    private final ClerkProfileClient clerk;
    private final ProjectInitializationStarter projectInitialization;
    private final ThreadPoolBulkhead bulkhead;
    private final OutboxProperties properties;
    private final String instanceId;
    private boolean profileSyncPausedLogged;

    public OutboxWorker(
            OutboxStore store,
            UserRepository users,
            ClerkProfileClient clerk,
            ProjectInitializationStarter projectInitialization,
            ThreadPoolBulkhead bulkhead,
            OutboxProperties properties,
            @Value("${breeze.observability.instance-id:local}") String instanceId
    ) {
        this.store = store;
        this.users = users;
        this.clerk = clerk;
        this.projectInitialization = projectInitialization;
        this.bulkhead = bulkhead;
        this.properties = properties;
        this.instanceId = instanceId;
    }

    @Scheduled(fixedDelayString = "${breeze.outbox.poll-interval-ms:1000}")
    public void poll() {
        if (!clerk.isConfigured()) {
            if (!profileSyncPausedLogged) {
                log.warn("profile_sync_outbox_paused reason=clerk_secret_key_missing");
                profileSyncPausedLogged = true;
            }
        } else if (profileSyncPausedLogged) {
            log.info("profile_sync_outbox_resumed");
            profileSyncPausedLogged = false;
        }
        for (int slot = 0; slot < properties.getWorkerThreads(); slot++) {
            try {
                bulkhead.executeRunnable(() -> {
                    try {
                        claimAndProcess();
                    } finally {
                        MDC.clear();
                    }
                });
            } catch (BulkheadFullException ignored) {
                // No in-memory queue: unclaimed work remains in PostgreSQL for the next poll.
                return;
            } catch (RuntimeException exception) {
                log.error("outbox_poll_failed exceptionType={}", exception.getClass().getSimpleName(), exception);
                return;
            }
        }
    }

    private void claimAndProcess() {
        try {
            String token = instanceId + ":" + UuidCreator.getTimeOrderedEpoch();
            store.claim(token, properties.getLeaseSeconds(), properties.getMaxAttempts(), clerk.isConfigured())
                    .ifPresent(this::process);
        } catch (RuntimeException exception) {
            log.error("outbox_claim_failed exceptionType={}", exception.getClass().getSimpleName(), exception);
        }
    }

    private void process(OutboxStore.Claim claim) {
        MDC.put("instanceId", instanceId);
        MDC.put("outboxEventId", claim.eventId().toString());
        MDC.put("aggregateId", claim.aggregateId().toString());
        if (claim.requestId() != null) {
            MDC.put("requestId", claim.requestId());
        }
        log.debug("outbox_delivery_started eventType={} attempt={}", claim.eventType(), claim.attempts());

        try {
            if (claim.attempts() > properties.getMaxAttempts()) {
                fail(claim, "LEASE_EXPIRED");
                return;
            }
            switch (claim.eventType()) {
                case USER_PROFILE_SYNC_REQUESTED -> {
                    var user = users.findById(claim.aggregateId());
                    if (user.isEmpty()) {
                        if (store.complete(claim, new ProfileSnapshot(null, null, null))) {
                            log.info("outbox_profile_sync_skipped reason=user_missing");
                        } else {
                            log.warn("outbox_claim_lost eventType={}", claim.eventType());
                        }
                        return;
                    }
                    MDC.put("clerkUserId", user.get().getClerkUserId());
                    ProfileSnapshot profile = clerk.fetch(user.get().getClerkUserId());
                    if (store.complete(claim, profile)) {
                        log.info("outbox_profile_sync_completed");
                    } else {
                        log.warn("outbox_claim_lost eventType={}", claim.eventType());
                    }
                }
                case PROJECT_WORKSPACE_INITIALIZATION_REQUESTED -> {
                    projectInitialization.start(claim);
                    if (store.completeProjectInitializationStart(claim)) {
                        log.info("project_initialization_workflow_started projectId={}", claim.aggregateId());
                    } else {
                        log.warn("outbox_claim_lost eventType={}", claim.eventType());
                    }
                }
                default -> throw new IllegalStateException("Unsupported outbox event type: " + claim.eventType());
            }
        } catch (Exception exception) {
            String code = exception instanceof ClerkError clerkError
                    ? "CLERK_HTTP_" + clerkError.code()
                    : exception.getClass().getSimpleName();
            log.debug("outbox_delivery_exception eventType={} errorCode={}", claim.eventType(), code, exception);
            fail(claim, code);
        }
    }

    private void fail(OutboxStore.Claim claim, String code) {
        int exponent = Math.min(claim.attempts() - 1, 10);
        int delay = (int) Math.min((long) properties.getRetryBaseSeconds() * (1L << exponent), 3600L);
        if (store.fail(claim, code, properties.getMaxAttempts(), delay)) {
            if (claim.attempts() >= properties.getMaxAttempts()) {
                log.error("outbox_delivery_dead eventType={} errorCode={} attempts={}",
                        claim.eventType(), code, claim.attempts());
            } else {
                log.warn("outbox_delivery_retry_scheduled eventType={} errorCode={} attempt={} delaySeconds={}",
                        claim.eventType(), code, claim.attempts(), delay);
            }
        } else {
            log.warn("outbox_claim_lost eventType={}", claim.eventType());
        }
    }
}
