package com.pulsevote.ballot;

import com.pulsevote.ballot.BallotModels.BallotReceiptResponse;
import com.pulsevote.ballot.BallotModels.SubmitBallotRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/events/{eventId}/ballots")
public class BallotController {
    private final BallotSubmissionService service;

    public BallotController(BallotSubmissionService service) { this.service = service; }

    @PostMapping
    ResponseEntity<BallotReceiptResponse> submit(
            @PathVariable UUID eventId,
            @RequestHeader("Idempotency-Key") UUID idempotencyKey,
            @Valid @RequestBody SubmitBallotRequest request) {
        BallotReceiptResponse response = service.submit(eventId, idempotencyKey, request);
        return response.replayed()
                ? ResponseEntity.ok(response)
                : ResponseEntity.created(URI.create("/v1/receipts/" + response.receiptId())).body(response);
    }
}

