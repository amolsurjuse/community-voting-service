package com.pulsevote.ballot;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class BallotModels {
    private BallotModels() {}

    public record SubmitBallotRequest(
            @NotNull UUID credentialId,
            @Positive int rulesVersion,
            @Positive int candidateVersion,
            @NotNull BallotType ballotType,
            @NotEmpty @Size(max = 50) List<UUID> candidateIds) {}

    public enum BallotType { SINGLE, MULTIPLE, RANKED }

    public record BallotReceiptResponse(
            UUID receiptId,
            String receiptToken,
            Instant acceptedTimeBucket,
            boolean replayed) {}
}
