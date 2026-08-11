# Community Voting Service

Correctness-first backend for PulseVote community voting. This is for informal community voting and does not claim one-person uniqueness, legal-election suitability, strict anonymity, or coercion resistance.

## Current capabilities

- PostgreSQL schemas for events, eligibility, immutable ballots, receipts, and outbox.
- Atomic eligibility consumption + ballot + receipt + outbox transaction.
- Database uniqueness for one accepted ballot per event credential and idempotency key.
- Database trigger preventing accepted-ballot updates or deletes.
- RFC 9457-style conflict responses.
- HS512 JWT validation for tokens issued by `auth-service`.
- Application-specific `COMMUNITY_VOTING_USER` authorization on every `/v1/**` API.
- Organizer-owned event draft, publish, close, archive, and duplicate APIs.
- Public discovery collections and invitation redemption.
- Privacy-safe results, aggregate analytics, export requests, and organizer activity APIs.

Health and info probes plus public event discovery are anonymous. Every organizer,
eligibility, ballot, results, analytics, and activity request must carry the dedicated
role, and `JWT_SECRET` must match `auth-service`.

## Local prerequisites

- Java 21
- Docker (for PostgreSQL integration tests)

## Verify

```powershell
.\mvnw.cmd verify
```
