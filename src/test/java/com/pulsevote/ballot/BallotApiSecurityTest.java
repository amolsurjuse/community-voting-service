package com.pulsevote.ballot;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pulsevote.ballot.BallotModels.BallotReceiptResponse;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:ballot-security;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.flyway.locations=classpath:db/migration-h2",
        "voting.receipt-token-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "voting.security.jwt-secret=0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
        "voting.security.issuer=auth-service",
        "voting.security.required-role=COMMUNITY_VOTING_USER"
})
class BallotApiSecurityTest {
    @Autowired MockMvc mvc;
    @MockitoBean BallotSubmissionService service;

    @Test
    void anonymousCallerIsRejected() throws Exception {
        mvc.perform(request()).andExpect(status().isUnauthorized());
    }

    @Test
    void unrelatedPlatformRoleIsForbidden() throws Exception {
        mvc.perform(request().with(jwt().authorities(new SimpleGrantedAuthority("ROLE_USER"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void communityVotingRoleCanSubmit() throws Exception {
        when(service.submit(any(), any(), any())).thenReturn(
                new BallotReceiptResponse(UUID.randomUUID(), "receipt", Instant.now(), false));
        mvc.perform(request().with(jwt().authorities(
                        new SimpleGrantedAuthority("ROLE_COMMUNITY_VOTING_USER"))))
                .andExpect(status().isCreated());
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request() {
        UUID eventId = UUID.randomUUID();
        return post("/v1/events/{eventId}/ballots", eventId)
                .header("Idempotency-Key", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"credentialId":"%s","rulesVersion":1,"candidateVersion":1,
                         "ballotType":"SINGLE","candidateIds":["%s"]}
                        """.formatted(UUID.randomUUID(), UUID.randomUUID()));
    }
}
