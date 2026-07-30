package com.pulsevote.ballot;

import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/events/{eventId}/eligibility")
public class EligibilityController {
    private final EligibilityService service;

    public EligibilityController(EligibilityService service) {
        this.service = service;
    }

    @PostMapping
    EligibilityService.EligibilityResponse issue(
            @PathVariable UUID eventId,
            Authentication authentication) {
        return service.issue(eventId, authentication.getName());
    }
}
