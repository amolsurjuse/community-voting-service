package com.pulsevote.event;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/public/events")
public class PublicEventController {
    private final JdbcTemplate jdbc;

    public PublicEventController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/{publicId}")
    ResponseEntity<EventResponse> get(@PathVariable String publicId) {
        List<EventRow> events = jdbc.query(
                "SELECT e.id,e.public_id,e.title,e.short_description,e.long_description,e.organizer_name,"
                        + "e.visibility,e.verification_level,e.result_visibility,e.status,e.starts_at,e.ends_at,"
                        + "e.rules_version,e.candidate_version,r.ballot_type,r.max_choices,"
                        + "COALESCE((r.canonical_json->>'minSelections')::int,1),"
                        + "COALESCE((r.canonical_json->>'requireFullRanking')::boolean,false) "
                        + "FROM event_domain.events e JOIN event_domain.rules r "
                        + "ON r.event_id=e.id AND r.version=e.rules_version "
                        + "WHERE e.public_id=? AND e.visibility IN ('PUBLIC','UNLISTED') AND e.status<>'DRAFT'",
                (rs, row) -> new EventRow(
                        rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9),
                        rs.getString(10), rs.getObject(11, java.time.OffsetDateTime.class).toInstant(),
                        rs.getObject(12, java.time.OffsetDateTime.class).toInstant(), rs.getInt(13),
                        rs.getInt(14), rs.getString(15), rs.getInt(16), rs.getInt(17), rs.getBoolean(18)),
                publicId);
        if (events.isEmpty()) return ResponseEntity.notFound().build();
        EventRow event = events.getFirst();
        List<CandidateResponse> candidates = jdbc.query(
                "SELECT id,name,display_order,status FROM event_domain.candidates "
                        + "WHERE event_id=? AND version=? ORDER BY display_order",
                (rs, row) -> new CandidateResponse(
                        rs.getObject(1, UUID.class), rs.getString(2), rs.getInt(3),
                        "ACTIVE".equals(rs.getString(4))),
                event.id(), event.candidateVersion());
        return ResponseEntity.ok(new EventResponse(
                event.id(), event.publicId(), event.title(), event.shortDescription(), event.longDescription(),
                event.organizerName(), event.visibility(), event.verificationLevel(), event.resultVisibility(),
                event.status(), event.startsAt(), event.endsAt(), event.rulesVersion(), event.candidateVersion(),
                new RulesResponse(event.ballotType(), event.minSelections(), event.maxChoices(),
                        event.requireFullRanking()), candidates));
    }

    record EventResponse(
            UUID id, String publicId, String title, String shortDescription, String longDescription,
            String organizerName, String visibility, String verificationLevel, String resultVisibility,
            String status, Instant startsAt, Instant endsAt, int rulesVersion, int candidateVersion,
            RulesResponse rules, List<CandidateResponse> candidates) {}
    record RulesResponse(String ballotType, int minSelections, int maxSelections, boolean requireFullRanking) {}
    record CandidateResponse(UUID id, String name, int order, boolean active) {}
    private record EventRow(
            UUID id, String publicId, String title, String shortDescription, String longDescription,
            String organizerName, String visibility, String verificationLevel, String resultVisibility,
            String status, Instant startsAt, Instant endsAt, int rulesVersion, int candidateVersion,
            String ballotType, int maxChoices, int minSelections, boolean requireFullRanking) {}
}
