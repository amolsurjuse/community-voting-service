# Community Voting Service

Correctness-first backend for PulseVote community voting. This is for informal community voting and does not claim one-person uniqueness, legal-election suitability, strict anonymity, or coercion resistance.

## Current slice

- PostgreSQL schemas for events, eligibility, immutable ballots, receipts, and outbox.
- Atomic eligibility consumption + ballot + receipt + outbox transaction.
- Database uniqueness for one accepted ballot per event credential and idempotency key.
- Database trigger preventing accepted-ballot updates or deletes.
- RFC 9457-style conflict responses.

The service is not ready for public deployment. Eligibility token verification, installation request signatures, organizer OIDC, event APIs, results, and worker delivery remain release blockers.

## Local prerequisites

- Java 21
- Docker (for PostgreSQL integration tests)

## Verify

```powershell
.\mvnw.cmd verify
```
