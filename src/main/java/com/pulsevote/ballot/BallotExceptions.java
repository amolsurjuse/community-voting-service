package com.pulsevote.ballot;

public final class BallotExceptions {
    private BallotExceptions() {}

    public static final class EventNotOpen extends RuntimeException {}
    public static final class CredentialUnavailable extends RuntimeException {}
    public static final class IdempotencyKeyReused extends RuntimeException {}
}

