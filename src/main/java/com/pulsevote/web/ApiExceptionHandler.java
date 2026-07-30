package com.pulsevote.web;

import com.pulsevote.ballot.BallotExceptions;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(BallotExceptions.EventNotOpen.class)
    ProblemDetail eventNotOpen() { return problem(HttpStatus.CONFLICT, "EVENT_NOT_OPEN", "Ballot not accepted"); }

    @ExceptionHandler(BallotExceptions.CredentialUnavailable.class)
    ProblemDetail credentialUnavailable() { return problem(HttpStatus.CONFLICT, "CREDENTIAL_ALREADY_CONSUMED", "Ballot not accepted"); }

    @ExceptionHandler(BallotExceptions.IdempotencyKeyReused.class)
    ProblemDetail idempotencyReused() { return problem(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED", "Idempotency key was used for another request"); }

    private static ProblemDetail problem(HttpStatus status, String code, String title) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(status, title);
        detail.setTitle(title);
        detail.setType(URI.create("https://api.pulsevote.example/problems/" + code.toLowerCase().replace('_', '-')));
        detail.setProperty("code", code);
        detail.setProperty("retryable", false);
        return detail;
    }
}

