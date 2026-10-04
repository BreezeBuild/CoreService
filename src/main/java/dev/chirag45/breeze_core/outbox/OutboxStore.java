package dev.chirag45.breeze_core.outbox;

import com.github.f4b6a3.uuid.UuidCreator;
import dev.chirag45.breeze_core.dto.translation.ProfileSnapshot;
import dev.chirag45.breeze_core.entities.ProjectEntity;
import dev.chirag45.breeze_core.enums.outbox.OutboxEventType;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Repository
public class OutboxStore {

    private final JdbcTemplate jdbc;

    public OutboxStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void requestInitialProfileSync(UUID userId) {
        jdbc.update("""
                INSERT INTO outbox_events (id, event_type, aggregate_id, payload)
                VALUES (?, 'USER_PROFILE_SYNC_REQUESTED', ?, jsonb_build_object('requestId', ?::text))
                ON CONFLICT (event_type, aggregate_id) DO NOTHING
                """, UuidCreator.getTimeOrderedEpoch(), userId, MDC.get("requestId"));
    }

    public void requestProjectInitialization(ProjectEntity project) {
        jdbc.update("""
                INSERT INTO outbox_events (id, event_type, aggregate_id, payload)
                VALUES (?, 'PROJECT_WORKSPACE_INITIALIZATION_REQUESTED', ?,
                    jsonb_build_object('requestId', ?::text, 'ownerUserId', ?::text,
                        'name', ?::text, 'description', ?::text))
                ON CONFLICT (event_type, aggregate_id) DO NOTHING
                """, UuidCreator.getTimeOrderedEpoch(), project.getId(), MDC.get("requestId"),
                project.getOwner().getId(), project.getName(), project.getDescription());
    }

    @Transactional
    public Optional<Claim> claim(String claimToken, int leaseSeconds, int maxAttempts, boolean profileSyncEnabled) {
        return jdbc.query("""
                WITH candidate AS (
                    SELECT id FROM outbox_events
                    WHERE ((status IN ('PENDING', 'FAILED') AND next_attempt_at <= now() AND attempts < ?)
                       OR (status = 'PROCESSING' AND locked_until < now()))
                      AND (event_type <> 'USER_PROFILE_SYNC_REQUESTED' OR ?)
                    ORDER BY created_at, id
                    FOR UPDATE SKIP LOCKED
                    LIMIT 1
                )
                UPDATE outbox_events AS event
                SET status = 'PROCESSING', attempts = event.attempts + 1,
                    locked_by = ?, locked_until = now() + (? * interval '1 second'), updated_at = now()
                FROM candidate WHERE event.id = candidate.id
                RETURNING event.id, event.event_type, event.aggregate_id, event.attempts,
                    event.payload ->> 'requestId' AS request_id,
                    event.payload ->> 'ownerUserId' AS owner_user_id,
                    event.payload ->> 'name' AS project_name,
                    event.payload ->> 'description' AS project_description
                """, (rs, rowNum) -> new Claim(
                rs.getObject("id", UUID.class),
                OutboxEventType.valueOf(rs.getString("event_type")),
                rs.getObject("aggregate_id", UUID.class),
                rs.getInt("attempts"), claimToken, rs.getString("request_id"),
                rs.getString("owner_user_id"), rs.getString("project_name"),
                rs.getString("project_description")
        ), maxAttempts, profileSyncEnabled, claimToken, leaseSeconds).stream().findFirst();
    }

    public Optional<Claim> claim(String claimToken, int leaseSeconds, int maxAttempts) {
        return claim(claimToken, leaseSeconds, maxAttempts, true);
    }

    public boolean completeProjectInitializationStart(Claim claim) {
        return jdbc.update("""
                UPDATE outbox_events SET status = 'COMPLETED', locked_by = NULL, locked_until = NULL,
                    last_error = NULL, completed_at = now(), updated_at = now()
                WHERE id = ? AND status = 'PROCESSING' AND locked_by = ?
                """, claim.eventId(), claim.claimToken()) == 1;
    }

    @Transactional
    public boolean complete(Claim claim, ProfileSnapshot profile) {
        int updated = jdbc.update("""
                UPDATE outbox_events SET status = 'COMPLETED', locked_by = NULL, locked_until = NULL,
                    last_error = NULL, completed_at = now(), updated_at = now()
                WHERE id = ? AND status = 'PROCESSING' AND locked_by = ?
                """, claim.eventId(), claim.claimToken());
        if (updated == 0) {
            return false;
        }
        jdbc.update("""
                UPDATE users SET email = ?, display_name = ?, avatar_url = ?,
                    profile_sync_status = 'SYNCED', profile_synced_at = now(), updated_at = now()
                WHERE id = ?
                """, profile.email(), profile.displayName(), profile.avatarUrl(), claim.aggregateId());
        return true;
    }

    @Transactional
    public boolean fail(Claim claim, String errorCode, int maxAttempts, int retryDelaySeconds) {
        boolean exhausted = claim.attempts() >= maxAttempts;
        int updated = jdbc.update("""
                UPDATE outbox_events SET status = ?, locked_by = NULL, locked_until = NULL,
                    last_error = ?, next_attempt_at = now() + (? * interval '1 second'), updated_at = now()
                WHERE id = ? AND status = 'PROCESSING' AND locked_by = ?
                """, exhausted ? "DEAD" : "FAILED", errorCode, retryDelaySeconds,
                claim.eventId(), claim.claimToken());
        if (updated == 0) {
            return false;
        }
        if (exhausted && claim.eventType() == OutboxEventType.USER_PROFILE_SYNC_REQUESTED) {
            jdbc.update("""
                    UPDATE users SET profile_sync_status = 'FAILED', updated_at = now()
                    WHERE id = ? AND profile_sync_status <> 'SYNCED'
                    """, claim.aggregateId());
        }
        return true;
    }

    public record Claim(UUID eventId, OutboxEventType eventType, UUID aggregateId, int attempts, String claimToken,
                        String requestId, String ownerUserId, String projectName, String projectDescription) { }

}
