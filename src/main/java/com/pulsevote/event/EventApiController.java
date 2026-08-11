package com.pulsevote.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/v1")
public class EventApiController {
    private static final String EVENT_COLUMNS = "e.id,e.public_id,e.title,e.short_description,e.long_description,"
            + "e.organizer_name,e.visibility,e.verification_level,e.result_visibility,e.status,e.starts_at,e.ends_at,"
            + "e.rules_version,e.candidate_version,e.category,e.cover_seed,e.cover_emoji,e.location,e.language,"
            + "e.organizer_verified,e.min_result_threshold,e.time_zone,e.created_at";

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public EventApiController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.json = new ObjectMapper();
    }

    @GetMapping("/events")
    List<Map<String, Object>> organizerEvents(Authentication auth) {
        return eventList("WHERE e.organizer_id=? ORDER BY e.updated_at DESC", auth.getName());
    }

    @PostMapping("/events")
    @Transactional
    Map<String, Object> create(@RequestBody JsonNode body, Authentication auth) {
        UUID id = uuid(body, "id", UUID.randomUUID());
        String publicId = text(body, "publicId", UUID.randomUUID().toString().substring(0, 8));
        Instant starts = instant(body, "startsAt", Instant.now());
        Instant ends = instant(body, "endsAt", starts.plus(7, ChronoUnit.DAYS));
        jdbc.update("INSERT INTO event_domain.events (id,public_id,status,starts_at,ends_at,rules_version,candidate_version,"
                        + "title,short_description,long_description,organizer_name,visibility,verification_level,result_visibility,"
                        + "organizer_id,category,cover_seed,cover_emoji,location,language,min_result_threshold,time_zone) "
                        + "VALUES (?,?,?,?,?,1,1,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                id, publicId, "DRAFT", starts, ends, text(body,"title","Untitled event"),
                text(body,"shortDescription",""), text(body,"longDescription",""),
                text(body,"organizerName","Community organizer"), upper(body,"visibility","PUBLIC"),
                upper(body,"verificationLevel","ACCOUNT"), upper(body,"resultVisibility","AFTER_CLOSE"), auth.getName(),
                upper(body,"category","COMMUNITY"), integer(body,"coverSeed",0), text(body,"coverEmoji",""),
                text(body,"location",""), text(body,"language","English"), integer(body,"minResultThreshold",0),
                text(body,"timeZone","UTC"));
        saveRulesAndCandidates(id, body);
        activity(auth.getName(), id, "EVENT_OPENED", "Draft created", "Your event draft is ready to edit.");
        return ownedEvent(id, auth.getName());
    }

    @PutMapping("/events/{id}")
    @Transactional
    Map<String, Object> update(@PathVariable UUID id, @RequestBody JsonNode body, Authentication auth) {
        requireOwner(id, auth.getName());
        Integer ballots = jdbc.queryForObject("SELECT count(*) FROM ballot_core.ballots WHERE event_id=?", Integer.class, id);
        if (ballots != null && ballots > 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "An event with ballots cannot be edited");
        Instant starts = instant(body, "startsAt", Instant.now());
        Instant ends = instant(body, "endsAt", starts.plus(7, ChronoUnit.DAYS));
        jdbc.update("UPDATE event_domain.events SET title=?,short_description=?,long_description=?,organizer_name=?,"
                        + "visibility=?,verification_level=?,result_visibility=?,starts_at=?,ends_at=?,category=?,cover_seed=?,"
                        + "cover_emoji=?,location=?,language=?,min_result_threshold=?,time_zone=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",
                text(body,"title","Untitled event"), text(body,"shortDescription",""), text(body,"longDescription",""),
                text(body,"organizerName","Community organizer"), upper(body,"visibility","PUBLIC"),
                upper(body,"verificationLevel","ACCOUNT"), upper(body,"resultVisibility","AFTER_CLOSE"), starts, ends,
                upper(body,"category","COMMUNITY"), integer(body,"coverSeed",0), text(body,"coverEmoji",""),
                text(body,"location",""), text(body,"language","English"), integer(body,"minResultThreshold",0),
                text(body,"timeZone","UTC"), id);
        jdbc.update("DELETE FROM event_domain.candidates WHERE event_id=?", id);
        jdbc.update("DELETE FROM event_domain.rules WHERE event_id=?", id);
        saveRulesAndCandidates(id, body);
        return ownedEvent(id, auth.getName());
    }

    @PostMapping("/events/{id}/{action:publish|close|archive}")
    @Transactional
    Map<String, Object> transition(@PathVariable UUID id, @PathVariable String action, Authentication auth) {
        requireOwner(id, auth.getName());
        String status = action.equals("publish") ? "OPEN" : action.equals("close") ? "CLOSED" : "ARCHIVED";
        jdbc.update("UPDATE event_domain.events SET status=?,updated_at=CURRENT_TIMESTAMP WHERE id=?", status, id);
        activity(auth.getName(), id, action.equals("publish") ? "EVENT_PUBLISHED" : action.equals("close") ? "EVENT_CLOSED" : "RESULTS_FINALIZED",
                "Event " + action + "d", "The event status changed to " + status.toLowerCase() + ".");
        return ownedEvent(id, auth.getName());
    }

    @PostMapping("/events/{id}/duplicate")
    Map<String, Object> duplicate(@PathVariable UUID id, Authentication auth) {
        JsonNode source = json.valueToTree(ownedEvent(id, auth.getName()));
        ((com.fasterxml.jackson.databind.node.ObjectNode) source).put("id", UUID.randomUUID().toString());
        ((com.fasterxml.jackson.databind.node.ObjectNode) source).put("publicId", UUID.randomUUID().toString().substring(0, 8));
        ((com.fasterxml.jackson.databind.node.ObjectNode) source).put("title", source.path("title").asText() + " (copy)");
        return create(source, auth);
    }

    @GetMapping("/public/events")
    List<Map<String, Object>> discover(@RequestParam(defaultValue="") String query, @RequestParam(defaultValue="0") int page) {
        return eventList("WHERE e.visibility='PUBLIC' AND e.status<>'DRAFT' AND lower(e.title) LIKE lower(?) "
                + "ORDER BY e.created_at DESC LIMIT 20 OFFSET ?", "%" + query + "%", Math.max(0,page) * 20);
    }

    @GetMapping("/public/events/{collection:trending|closing-soon|newly-published}")
    List<Map<String, Object>> collection(@PathVariable String collection) {
        String order = collection.equals("trending") ? "ballot_count DESC,e.created_at DESC"
                : collection.equals("closing-soon") ? "e.ends_at ASC" : "e.created_at DESC";
        return eventList("WHERE e.visibility='PUBLIC' AND e.status IN ('OPEN','SCHEDULED','CLOSED') ORDER BY " + order + " LIMIT 20");
    }

    @GetMapping("/public/invitations/{token}")
    ResponseEntity<Map<String, Object>> invitation(@PathVariable String token) {
        List<UUID> ids = jdbc.query("SELECT event_id FROM event_domain.invitations WHERE token_hash=? "
                        + "AND expires_at>CURRENT_TIMESTAMP AND redeemed_at IS NULL",
                (rs,row) -> rs.getObject(1,UUID.class), sha256(token));
        return ids.isEmpty() ? ResponseEntity.notFound().build() : ResponseEntity.ok(event(ids.getFirst()));
    }

    @GetMapping("/events/{id}/results")
    Map<String, Object> results(@PathVariable UUID id, Authentication auth) {
        requireVisibleOrOwner(id, auth.getName());
        List<Map<String,Object>> tallies = jdbc.query("SELECT c.id,COALESCE(sum(CASE WHEN convert_from(b.encrypted_payload,'UTF8') "
                        + "LIKE '%\"' || c.id || '\"%' THEN 1 ELSE 0 END),0) FROM event_domain.candidates c "
                        + "LEFT JOIN ballot_core.ballots b ON b.event_id=c.event_id WHERE c.event_id=? "
                        + "AND c.version=(SELECT candidate_version FROM event_domain.events WHERE id=?) GROUP BY c.id",
                (rs,row) -> Map.of("candidateId",rs.getObject(1,UUID.class),"votes",rs.getInt(2)), id,id);
        int total = jdbc.queryForObject("SELECT count(*) FROM ballot_core.ballots WHERE event_id=?",Integer.class,id);
        Map<String,Object> e = event(id);
        return Map.of("eventId",id,"totalBallots",total,"tallies",tallies,"rankedRounds",List.of(),
                "updatedAt",Instant.now(),"isFinal",List.of("CLOSED","ARCHIVED","RESULTS_FINALIZED").contains(e.get("status")),
                "belowThreshold",total < (int)e.get("minResultThreshold"));
    }

    @GetMapping("/events/{id}/analytics")
    Map<String, Object> analytics(@PathVariable UUID id, Authentication auth) {
        requireOwner(id, auth.getName());
        int ballots = jdbc.queryForObject("SELECT count(*) FROM ballot_core.ballots WHERE event_id=?",Integer.class,id);
        int credentials = jdbc.queryForObject("SELECT count(*) FROM verification.eligibility_credentials WHERE event_id=?",Integer.class,id);
        List<Integer> hourly = jdbc.query("SELECT count(*) FROM ballot_core.ballots WHERE event_id=? "
                + "GROUP BY date_trunc('hour',accepted_at) ORDER BY date_trunc('hour',accepted_at)",(rs,row)->rs.getInt(1),id);
        return Map.of("eventId",id,"acceptedBallots",ballots,"participationByHour",hourly,
                "verificationStarted",credentials,"verificationCompleted",credentials,"failedVerificationAttempts",0,
                "suspiciousIndicators",List.of(),"platformSplit",Map.of());
    }

    @PostMapping("/events/{id}/exports")
    void export(@PathVariable UUID id, Authentication auth) {
        requireOwner(id,auth.getName());
        activity(auth.getName(),id,"EXPORT_READY","Export ready","Your privacy-safe analytics export is ready.");
    }

    @GetMapping("/activity")
    List<Map<String,Object>> activity(Authentication auth) {
        return jdbc.query("SELECT id,kind,title,body,occurred_at,event_id,read_at IS NOT NULL FROM operations.activity "
                        + "WHERE organizer_id=? ORDER BY occurred_at DESC LIMIT 100",
                (rs,row)->Map.of("id",rs.getObject(1,UUID.class),"kind",rs.getString(2),"title",rs.getString(3),
                        "body",rs.getString(4),"occurredAt",rs.getObject(5,java.time.OffsetDateTime.class).toInstant(),
                        "eventId",rs.getObject(6)==null ? "" : rs.getObject(6,UUID.class).toString(),"read",rs.getBoolean(7)),auth.getName());
    }

    @PatchMapping("/activity/read")
    void markRead(Authentication auth) {
        jdbc.update("UPDATE operations.activity SET read_at=CURRENT_TIMESTAMP WHERE organizer_id=? AND read_at IS NULL",auth.getName());
    }

    private void saveRulesAndCandidates(UUID id, JsonNode body) {
        JsonNode rules = body.path("rules");
        String type = upper(rules,"ballotType","SINGLE").replace("_CHOICE","");
        int max = integer(rules,"maxSelections",1);
        jdbc.update("INSERT INTO event_domain.rules(event_id,version,ballot_type,max_choices,canonical_json) VALUES (?,?,?, ?,CAST(? AS jsonb))",
                id,1,type,max,rules.isMissingNode() ? "{}" : rules.toString());
        int order=0;
        for (JsonNode c : body.path("candidates")) {
            jdbc.update("INSERT INTO event_domain.candidates(event_id,id,version,display_order,status,name,subtitle,description,organization,organizer_notes,color_seed) VALUES (?,?,1,?,?,?,?,?,?,?,?)",
                    id,uuid(c,"id",UUID.randomUUID()),integer(c,"order",order++),c.path("active").asBoolean(true)?"ACTIVE":"WITHDRAWN",
                    text(c,"name","Option"),text(c,"subtitle",""),text(c,"description",""),text(c,"organization",""),
                    text(c,"organizerNotes",""),integer(c,"colorSeed",0));
        }
    }

    private List<Map<String,Object>> eventList(String suffix,Object... args) {
        return jdbc.query("SELECT " + EVENT_COLUMNS + ",(SELECT count(*) FROM ballot_core.ballots b WHERE b.event_id=e.id) ballot_count "
                        + "FROM event_domain.events e " + suffix,
                (rs,row)->event(rs),args);
    }

    private Map<String,Object> event(UUID id) {
        List<Map<String,Object>> rows=eventList("WHERE e.id=?",id);
        if(rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return rows.getFirst();
    }

    private Map<String,Object> ownedEvent(UUID id,String owner) { requireOwner(id,owner); return event(id); }
    private void requireOwner(UUID id,String owner) {
        Integer count=jdbc.queryForObject("SELECT count(*) FROM event_domain.events WHERE id=? AND organizer_id=?",Integer.class,id,owner);
        if(count==null||count==0) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
    private void requireVisibleOrOwner(UUID id,String owner) {
        Integer count=jdbc.queryForObject("SELECT count(*) FROM event_domain.events WHERE id=? AND (organizer_id=? OR visibility<>'PRIVATE')",Integer.class,id,owner);
        if(count==null||count==0) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }

    private Map<String,Object> event(java.sql.ResultSet rs) throws java.sql.SQLException {
        UUID id=rs.getObject(1,UUID.class); Map<String,Object> value=new LinkedHashMap<>();
        value.put("id",id); value.put("publicId",rs.getString(2)); value.put("title",rs.getString(3));
        value.put("shortDescription",rs.getString(4)); value.put("longDescription",rs.getString(5)); value.put("organizerName",rs.getString(6));
        value.put("visibility",rs.getString(7)); value.put("verificationLevel",rs.getString(8)); value.put("resultVisibility",rs.getString(9));
        value.put("status",rs.getString(10)); value.put("startsAt",rs.getObject(11,java.time.OffsetDateTime.class).toInstant());
        value.put("endsAt",rs.getObject(12,java.time.OffsetDateTime.class).toInstant()); value.put("rulesVersion",rs.getInt(13)); value.put("candidateVersion",rs.getInt(14));
        value.put("category",rs.getString(15)); value.put("coverSeed",rs.getInt(16)); value.put("coverEmoji",rs.getString(17));
        value.put("location",rs.getString(18)); value.put("language",rs.getString(19)); value.put("organizerVerified",rs.getBoolean(20));
        value.put("minResultThreshold",rs.getInt(21)); value.put("timeZone",rs.getString(22)); value.put("createdAt",rs.getObject(23,java.time.OffsetDateTime.class).toInstant());
        value.put("totalBallots",rs.getInt(24));
        Map<String,Object> rules=jdbc.queryForObject("SELECT ballot_type,max_choices,canonical_json FROM event_domain.rules WHERE event_id=? AND version=?",
                (rr,row)->Map.of("ballotType",rr.getString(1),"minSelections",1,"maxSelections",rr.getInt(2),"requireFullRanking",false),id,rs.getInt(13));
        value.put("rules",rules);
        value.put("candidates",jdbc.query("SELECT id,name,subtitle,description,organization,organizer_notes,color_seed,display_order,status FROM event_domain.candidates WHERE event_id=? AND version=? ORDER BY display_order",
                (cr,row)->Map.of("id",cr.getObject(1,UUID.class),"name",cr.getString(2),"subtitle",cr.getString(3),"description",cr.getString(4),
                        "organization",cr.getString(5),"organizerNotes",cr.getString(6),"colorSeed",cr.getInt(7),"order",cr.getInt(8),"active","ACTIVE".equals(cr.getString(9))),id,rs.getInt(14)));
        return value;
    }

    private void activity(String owner,UUID event,String kind,String title,String body) {
        jdbc.update("INSERT INTO operations.activity(id,organizer_id,event_id,kind,title,body) VALUES (?,?,?,?,?,?)",UUID.randomUUID(),owner,event,kind,title,body);
    }
    private static String text(JsonNode n,String key,String fallback){String v=n.path(key).asText();return v.isBlank()?fallback:v;}
    private static String upper(JsonNode n,String key,String fallback){return text(n,key,fallback).replaceAll("([a-z])([A-Z])","$1_$2").toUpperCase();}
    private static int integer(JsonNode n,String key,int fallback){return n.hasNonNull(key)?n.path(key).asInt():fallback;}
    private static UUID uuid(JsonNode n,String key,UUID fallback){try{return UUID.fromString(n.path(key).asText());}catch(Exception e){return fallback;}}
    private static Instant instant(JsonNode n,String key,Instant fallback){try{return Instant.parse(n.path(key).asText());}catch(Exception e){return fallback;}}
    private static byte[] sha256(String value){try{return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));}catch(Exception e){throw new IllegalStateException(e);}}
}
