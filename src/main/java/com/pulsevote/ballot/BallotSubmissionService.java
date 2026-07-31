package com.pulsevote.ballot;

import com.pulsevote.ballot.BallotModels.BallotReceiptResponse;
import com.pulsevote.ballot.BallotModels.SubmitBallotRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BallotSubmissionService {
    private final JdbcTemplate jdbc;
    private final ReceiptTokenService receiptTokens;

    public BallotSubmissionService(JdbcTemplate jdbc, ReceiptTokenService receiptTokens) {
        this.jdbc = jdbc;
        this.receiptTokens = receiptTokens;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public BallotReceiptResponse submit(UUID eventId, UUID idempotencyKey, SubmitBallotRequest request) {
        byte[] canonicalPayload = canonicalPayload(request);
        byte[] requestDigest = sha256(canonicalPayload);

        BallotReceiptResponse replay = findByIdempotency(eventId, idempotencyKey, requestDigest);
        if (replay != null) return replay;

        EventSnapshot event = lockOpenEvent(eventId);
        if (event == null || event.rulesVersion() != request.rulesVersion()
                || event.candidateVersion() != request.candidateVersion()) {
            throw new BallotExceptions.EventNotOpen();
        }
        validateSelection(eventId, event, request);

        int consumed = jdbc.update(
                "UPDATE verification.eligibility_credentials "
                        + "SET status='CONSUMED', consumed_at=CURRENT_TIMESTAMP "
                        + "WHERE id=? AND event_id=? AND status='ELIGIBLE' "
                        + "AND expires_at>CURRENT_TIMESTAMP",
                request.credentialId(), eventId);
        if (consumed != 1) throw new BallotExceptions.CredentialUnavailable();

        UUID ballotId = UUID.randomUUID();
        UUID receiptId = UUID.randomUUID();
        String receiptToken = receiptTokens.tokenFor(receiptId);
        byte[] payloadDigest = sha256(canonicalPayload);

        jdbc.update("INSERT INTO ballot_core.ballots "
                        + "(id,event_id,credential_id,idempotency_key,request_digest,rules_version,candidate_version,ballot_type,encrypted_payload,payload_digest) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?)",
                ballotId, eventId, request.credentialId(), idempotencyKey, requestDigest,
                request.rulesVersion(), request.candidateVersion(), request.ballotType().name(), canonicalPayload, payloadDigest);

        Instant acceptedAt = jdbc.queryForObject("SELECT CURRENT_TIMESTAMP", java.time.OffsetDateTime.class).toInstant();
        Instant bucket = Instant.ofEpochSecond(acceptedAt.getEpochSecond() - Math.floorMod(acceptedAt.getEpochSecond(), 300));
        jdbc.update("INSERT INTO ballot_core.receipts "
                        + "(id,ballot_id,public_token_hash,accepted_time_bucket,signature) VALUES (?,?,?,?,?)",
                receiptId, ballotId, sha256(receiptToken.getBytes(StandardCharsets.UTF_8)), Timestamp.from(bucket),
                sha256((receiptId + ":" + ballotId + ":" + eventId).getBytes(StandardCharsets.UTF_8)));
        jdbc.update("UPDATE event_domain.events SET first_ballot_at = COALESCE(first_ballot_at, CURRENT_TIMESTAMP) WHERE id = ?", eventId);
        jdbc.update("INSERT INTO operations.outbox (id,aggregate_type,aggregate_id,event_type,payload) "
                        + "VALUES (?, 'Ballot', ?, 'BallotAccepted.v1', CAST(? AS jsonb))",
                UUID.randomUUID(), ballotId,
                "{\"eventId\":\"" + eventId + "\",\"ballotId\":\"" + ballotId + "\"}");

        return new BallotReceiptResponse(receiptId, receiptToken, bucket, false);
    }

    private BallotReceiptResponse findByIdempotency(UUID eventId, UUID key, byte[] digest) {
        List<ExistingReceipt> rows = jdbc.query(
                "SELECT r.id,r.accepted_time_bucket,b.request_digest "
                        + "FROM ballot_core.ballots b JOIN ballot_core.receipts r ON r.ballot_id=b.id "
                        + "WHERE b.event_id=? AND b.idempotency_key=?",
                (rs, row) -> existingReceipt(rs), eventId, key);
        if (rows.isEmpty()) return null;
        ExistingReceipt row = rows.getFirst();
        if (!MessageDigest.isEqual(row.requestDigest(), digest)) throw new BallotExceptions.IdempotencyKeyReused();
        return new BallotReceiptResponse(row.id(), receiptTokens.tokenFor(row.id()), row.acceptedTimeBucket(), true);
    }

    private EventSnapshot lockOpenEvent(UUID eventId) {
        List<EventSnapshot> rows = jdbc.query(
                "SELECT e.rules_version,e.candidate_version,r.ballot_type,r.max_choices "
                        + "FROM event_domain.events e JOIN event_domain.rules r ON r.event_id=e.id AND r.version=e.rules_version "
                        + "WHERE e.id=? AND e.status='OPEN' "
                        + "AND CURRENT_TIMESTAMP>=e.starts_at AND CURRENT_TIMESTAMP<e.ends_at "
                        + "FOR UPDATE",
                (rs, row) -> new EventSnapshot(rs.getInt(1), rs.getInt(2),
                        BallotModels.BallotType.valueOf(rs.getString(3)), rs.getInt(4)), eventId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private void validateSelection(UUID eventId, EventSnapshot event, SubmitBallotRequest request) {
        int size = request.candidateIds().size();
        if (request.ballotType() != event.ballotType()
                || (request.ballotType() == BallotModels.BallotType.SINGLE && size != 1)
                || (request.ballotType() != BallotModels.BallotType.SINGLE && (size < 1 || size > event.maxChoices()))) {
            throw new IllegalArgumentException("Ballot does not satisfy the published rules");
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(size, "?"));
        List<Object> parameters = new java.util.ArrayList<>();
        parameters.add(eventId);
        parameters.add(request.candidateVersion());
        parameters.addAll(request.candidateIds());
        Integer matches = jdbc.queryForObject(
                "SELECT count(*) FROM event_domain.candidates WHERE event_id=? AND version=? "
                        + "AND status='ACTIVE' AND id IN (" + placeholders + ")",
                Integer.class, parameters.toArray());
        if (matches == null || matches != size) throw new IllegalArgumentException("Ballot contains an invalid candidate");
    }

    private byte[] canonicalPayload(SubmitBallotRequest request) {
        if (request.candidateIds().stream().distinct().count() != request.candidateIds().size()) {
            throw new IllegalArgumentException("candidateIds must be unique");
        }
        String candidates = request.candidateIds().stream()
                .map(id -> "\"" + id + "\"")
                .reduce((left, right) -> left + "," + right)
                .orElseThrow();
        String canonical = "{\"ballotType\":\"" + request.ballotType().name()
                + "\",\"candidateIds\":[" + candidates + "]"
                + ",\"candidateVersion\":" + request.candidateVersion()
                + ",\"rulesVersion\":" + request.rulesVersion() + "}";
        return canonical.getBytes(StandardCharsets.UTF_8);
    }

    private static ExistingReceipt existingReceipt(ResultSet rs) throws SQLException {
        return new ExistingReceipt(rs.getObject(1, UUID.class), rs.getObject(2, java.time.OffsetDateTime.class).toInstant(), rs.getBytes(3));
    }

    private static byte[] sha256(byte[] value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    private record EventSnapshot(int rulesVersion, int candidateVersion, BallotModels.BallotType ballotType, int maxChoices) {}
    private record ExistingReceipt(UUID id, Instant acceptedTimeBucket, byte[] requestDigest) {}
}
