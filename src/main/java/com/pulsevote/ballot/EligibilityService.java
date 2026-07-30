package com.pulsevote.ballot;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EligibilityService {
    private final JdbcTemplate jdbc;

    public EligibilityService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public EligibilityResponse issue(UUID eventId, String userId) {
        List<EventVersion> events = jdbc.query(
                "SELECT rules_version,candidate_version FROM event_domain.events "
                        + "WHERE id=? AND status='OPEN' AND transaction_timestamp()>=starts_at "
                        + "AND transaction_timestamp()<ends_at FOR KEY SHARE",
                (rs, row) -> new EventVersion(rs.getInt(1), rs.getInt(2)), eventId);
        if (events.isEmpty()) throw new BallotExceptions.EventNotOpen();

        byte[] commitment = sha256((eventId + ":" + userId).getBytes(StandardCharsets.UTF_8));
        UUID proposedId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO verification.eligibility_credentials "
                        + "(id,event_id,tier,subject_commitment,policy_version,status,expires_at) "
                        + "SELECT ?,?,'ACCOUNT',?,1,'ELIGIBLE',ends_at FROM event_domain.events WHERE id=? "
                        + "ON CONFLICT (event_id,subject_commitment,policy_version) DO NOTHING",
                proposedId, eventId, commitment, eventId);
        UUID credentialId = jdbc.queryForObject(
                "SELECT id FROM verification.eligibility_credentials "
                        + "WHERE event_id=? AND subject_commitment=? AND policy_version=1",
                UUID.class, eventId, commitment);
        EventVersion version = events.getFirst();
        return new EligibilityResponse(credentialId, version.rulesVersion(), version.candidateVersion());
    }

    private static byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    public record EligibilityResponse(UUID credentialId, int rulesVersion, int candidateVersion) {}
    private record EventVersion(int rulesVersion, int candidateVersion) {}
}
