package com.pulsevote.ballot;

import static org.assertj.core.api.Assertions.assertThat;

import com.pulsevote.ballot.BallotModels.BallotReceiptResponse;
import com.pulsevote.ballot.BallotModels.BallotType;
import com.pulsevote.ballot.BallotModels.SubmitBallotRequest;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest
class BallotSubmissionConcurrencyTest {
    private static final String TEST_RECEIPT_KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> required("TEST_DATABASE_URL"));
        registry.add("spring.datasource.username", () -> required("TEST_DATABASE_USERNAME"));
        registry.add("spring.datasource.password", () -> required("TEST_DATABASE_PASSWORD"));
        registry.add("voting.receipt-token-key", () -> TEST_RECEIPT_KEY);
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required for PostgreSQL tests");
        return value;
    }

    @Autowired BallotSubmissionService service;
    @Autowired JdbcTemplate jdbc;

    @Test
    void exactlyOneOfConcurrentRequestsConsumesTheCredential() throws Exception {
        UUID eventId = openEvent();
        UUID credentialId = eligibleCredential(eventId);
        SubmitBallotRequest request = request(credentialId);
        int attempts = 100;

        try (ExecutorService executor = Executors.newFixedThreadPool(32)) {
            List<Callable<Object>> calls = new ArrayList<>();
            for (int i = 0; i < attempts; i++) {
                calls.add(() -> {
                    try { return service.submit(eventId, UUID.randomUUID(), request); }
                    catch (BallotExceptions.CredentialUnavailable expected) { return expected; }
                });
            }
            List<Future<Object>> futures = executor.invokeAll(calls);
            long accepted = 0;
            long rejected = 0;
            for (Future<Object> future : futures) {
                Object outcome = future.get();
                if (outcome instanceof BallotReceiptResponse) accepted++;
                if (outcome instanceof BallotExceptions.CredentialUnavailable) rejected++;
            }
            assertThat(accepted).isEqualTo(1);
            assertThat(rejected).isEqualTo(attempts - 1);
        }

        assertThat(jdbc.queryForObject("SELECT count(*) FROM ballot_core.ballots WHERE event_id=?", Long.class, eventId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM operations.outbox WHERE payload->>'eventId'=?", Long.class, eventId.toString())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM verification.eligibility_credentials WHERE id=?", String.class, credentialId)).isEqualTo("CONSUMED");
    }

    @Test
    void identicalIdempotentRetryReturnsTheOriginalReceiptAndToken() {
        UUID eventId = openEvent();
        UUID credentialId = eligibleCredential(eventId);
        UUID key = UUID.randomUUID();
        SubmitBallotRequest request = request(credentialId);

        BallotReceiptResponse first = service.submit(eventId, key, request);
        BallotReceiptResponse retry = service.submit(eventId, key, request);

        assertThat(first.replayed()).isFalse();
        assertThat(retry.replayed()).isTrue();
        assertThat(retry.receiptId()).isEqualTo(first.receiptId());
        assertThat(retry.receiptToken()).isEqualTo(first.receiptToken());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ballot_core.ballots WHERE event_id=?", Long.class, eventId)).isEqualTo(1);
    }

    @Test
    void databaseRejectsMutationOfAnAcceptedBallot() {
        UUID eventId = openEvent();
        UUID credentialId = eligibleCredential(eventId);
        service.submit(eventId, UUID.randomUUID(), request(credentialId));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update(
                "UPDATE ballot_core.ballots SET ballot_type='MULTIPLE' WHERE event_id=?", eventId))
                .hasMessageContaining("accepted ballots are immutable");
    }

    private UUID openEvent() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO event_domain.events "
                        + "(id,public_id,status,starts_at,ends_at,rules_version,candidate_version) "
                        + "VALUES (?,?,'OPEN',?,?,1,1)",
                id, "event-" + id, Timestamp.from(Instant.now().minus(1, ChronoUnit.HOURS)), Timestamp.from(Instant.now().plus(1, ChronoUnit.HOURS)));
        jdbc.update("INSERT INTO event_domain.rules (event_id,version,ballot_type,max_choices,canonical_json) "
                + "VALUES (?,1,'SINGLE',1,CAST('{}' AS jsonb))", id);
        return id;
    }

    private UUID eligibleCredential(UUID eventId) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO verification.eligibility_credentials "
                        + "(id,event_id,tier,subject_commitment,policy_version,status,expires_at) "
                        + "VALUES (?,?,'INVITATION',?,1,'ELIGIBLE',?)",
                id, eventId, UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8), Timestamp.from(Instant.now().plus(1, ChronoUnit.HOURS)));
        return id;
    }

    private SubmitBallotRequest request(UUID credentialId) {
        UUID eventId = jdbc.queryForObject(
                "SELECT event_id FROM verification.eligibility_credentials WHERE id=?", UUID.class, credentialId);
        UUID candidateId = UUID.randomUUID();
        jdbc.update("INSERT INTO event_domain.candidates (event_id,id,version,display_order,status,name) "
                + "VALUES (?,?,1,0,'ACTIVE','Candidate')", eventId, candidateId);
        return new SubmitBallotRequest(credentialId, 1, 1, BallotType.SINGLE, List.of(candidateId));
    }
}
