# WS7b — Developer Verification: Admin Decision Flow — Design

> **Status:** approved by the user in conversation on 2026-09-28. Narrows the parent's WS7 section in
> `2026-09-23-agent-store-developer-platform-and-sandbox-design.md` and the follow-ups list in
> `2026-09-27-ws7a-developer-verification-design.md` §1/§7; where they differ, this document wins. Backend and
> contract only — no frontend exists in this repo yet; the admin console UI is FE-4, out of scope here. Decisions
> that were inferred rather than explicitly stated are marked `[I#]` and listed in §6.

## 1. Goal and scope

WS7a built the developer-facing submission form (`DRAFT → PENDING`, no checks run). WS7b is the admin side: a
platform reviewer inspects a `PENDING` submission, records a manual PASS/FAIL for each identity check, and decides
the outcome. A `VERIFIED` decision is what actually unblocks BYOK (`ByokKeyService`, gated since WS3) and moves the
org into the verified sandbox-quota tier (`SandboxQuotaProperties`, since WS6a) — both already read
`Org.developerVerifiedAt`; nothing before WS7b ever sets it.

**In scope (7b)**
- `verification_check` table, one append-only row per check attempt (§2).
- `KycProvider` interface + `ManualKycProvider` implementation (§3).
- `GET /api/v1/admin/verifications` (queue) and `GET /api/v1/admin/verifications/{id}` (detail) (§4).
- `POST /api/v1/admin/verifications/{id}/decision` (§4).
- Setting `Org.developerVerifiedAt` on a `VERIFIED` decision.
- `ONTOLOGY.md` update noting these fields are now wired.

**Out of scope (with owner)**
- Public publisher profile (`GET /api/v1/publishers/{orgId}`): split out per user decision on 2026-09-28, deferred
  to a later, unscheduled follow-up (not tied to a workstream number yet). It is a public, unauthenticated,
  buyer-facing endpoint with nothing in common with admin review beyond reading the same table. [I1]
- A separate `Org.verificationStatus` column: `developer_verification.status` is already the queryable source of
  truth (join on `org_id`); a mirror column on `Org` would duplicate state with no reader that needs it. [I2]
- Automated PAN/GSTIN/phone vendor checks: `KycProvider` stays an interface with only `ManualKycProvider`; a real
  vendor integration is unscheduled future work, same reasoning as WS7a §1.
- Any change to the developer-facing endpoints from WS7a (`GET`/`PATCH .../verification`, `.../pan-document`,
  `.../submit`) — those are unchanged; `FIX_REQUESTED` reopens editing through the guard WS7a already built
  (`requireEditable` permits `DRAFT`/`FIX_REQUESTED`).
- Admin console UI: FE-4.

## 2. Data model (migration V37)

```sql
CREATE TABLE verification_check (
    id UUID PRIMARY KEY,
    verification_id UUID NOT NULL REFERENCES developer_verification(id),
    check_type VARCHAR(20) NOT NULL,       -- PAN, GSTIN, PHONE
    provider VARCHAR(20) NOT NULL,         -- MANUAL (only value today)
    passed BOOLEAN NOT NULL,
    note TEXT,
    checked_by_user_id UUID NOT NULL REFERENCES app_user(id),
    checked_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_verification_check_verification_id ON verification_check(verification_id);
```

- Append-only, same convention as `RunStep`/`GateRequest`: a resubmission cycle (`FIX_REQUESTED → PENDING` again)
  gets a fresh row per check on the next decision rather than overwriting the old one, so the full history survives.
  The most recent row per `(verification_id, check_type)` is the current verdict.
- No unique constraint on `(verification_id, check_type)` — deliberately, to keep the history append-only.
- `developer_verification.reviewer_user_id`, `decision_note`, `decided_at` (reserved by WS7a's V36, unused until
  now) are written by the decision itself — one set of values for the whole submission, not per check.
- No new column on `Org` beyond the existing `developer_verified_at` [I2].

## 3. `KycProvider` seam

```java
public interface KycProvider {
    VerificationCheck check(DeveloperVerification v, CheckType type, boolean passed, String note, UUID adminUserId);
}
```

`ManualKycProvider` is the sole `@Component` implementation: it builds and saves a `verification_check` row from
the admin's own input — no vendor call, no automated verdict. The interface exists solely so a later automated
provider (a real PAN/GSTIN/phone vendor API) can implement the same contract without changing
`DeveloperVerificationReviewService` or the controller. `CheckType`: `PAN, GSTIN, PHONE` (an enum; `AGREEMENT` and
`ABOUT` need no check — they're either self-attested or free text with no identity claim to verify).

## 4. Admin API

All endpoints: `@PreAuthorize("hasRole('ADMIN')")`, same role and `SecurityContextHolder`/`JwtService.Claims`
pattern as `ReviewController` (`ADMIN` is the platform-wide reviewer role, not an org admin — see `UserRole`).
New controller `AdminVerificationController` in the existing `com.agentstore.platform.verification` package.

**`GET /api/v1/admin/verifications`**
Query param `status` (default `PENDING`). Returns a flat list: `id`, `orgId`, `orgName`, `legalName`,
`entityType`, `submittedAt`, `resubmissionCount`, `status`. Backed by
`DeveloperVerificationRepository.findByStatus(...)` joined to `Org` for the name (mirrors `ReviewController.queue()`
/ `ReviewItemRepository.findByStatus`).

**`GET /api/v1/admin/verifications/{id}`**
Full detail, 404 if missing (same `ResponseStatusException` pattern as `ReviewController.detail`):
`orgId`, `orgName`, `status`, `currentStep`, `entityType`, `legalName`, `displayName`, `country`, `city`,
`panMasked`, `gstinMasked`, `phoneMasked`, `panDocumentUrl` (a 15-minute pre-signed GET via
`ObjectStorage.presignGet`, same TTL convention as `BundleService`'s presigned PUT), `agreementVersion`,
`agreementAcceptedAt`, `submittedAt`, `resubmissionCount`, `reviewerUserId`, `decisionNote`, `decidedAt`, and
`checks: []` — every `verification_check` row for this verification, newest first, so the admin sees the full
history across resubmissions.

**`POST /api/v1/admin/verifications/{id}/decision`**
```json
{
  "outcome": "VERIFIED | FIX_REQUESTED | REJECTED",
  "notes": "string, optional",
  "checks": [
    { "type": "PAN", "passed": true, "note": "string, optional" }
  ]
}
```
One endpoint with an outcome discriminator (not `/verify` + `/reject` like `ReviewController`'s two-endpoint
shape) because there are three post-review outcomes, not two [I3]. `checks` is optional and may list any subset of
`PAN`/`GSTIN`/`PHONE` — a `FIX_REQUESTED` decision naming only the failing check(s) is the expected shape for a
partial rejection; `VERIFIED` is expected (not enforced) to include a passed row for every check type, but the
endpoint does not require it — the admin's stated outcome is authoritative, matching `ReviewController.approve`'s
own trust of the caller.

`DeveloperVerificationReviewService.decide(id, adminUserId, outcome, notes, checks)`:
1. Row-locks the `developer_verification` row (`findByIdForUpdate`, same pattern as WS7a's `lockOrCreate`, to close
   the same class of concurrent-decision race WS7a's fix (`e61e1c1`) closed for first-touch).
2. Guards `status == PENDING`, else `409 VERIFICATION_NOT_PENDING` (mirrors `ReviewItem.pendingItem`'s "already
   decided" guard, adapted to this table's richer status set).
3. For each entry in `checks`, calls `KycProvider.check(...)` (→ `ManualKycProvider`), saving one
   `verification_check` row per entry.
4. Sets `reviewerUserId = adminUserId`, `decisionNote = notes`, `decidedAt = now()`.
5. Sets `status` from `outcome` directly (`VERIFIED`, `FIX_REQUESTED`, or `REJECTED` — the request's enum values
   are exactly the three non-`PENDING` targets `developer_verification.status` allows here).
6. On `VERIFIED` only: `org.developerVerifiedAt = now()`, saved through the existing `Org` row.
7. Returns the updated detail view (§4's `GET .../{id}` shape).

`REJECTED` is terminal — no further `PATCH`/`submit` is possible (`requireEditable` in WS7a's service only ever
allows `DRAFT`/`FIX_REQUESTED`), matching `AgentVersion.REJECTED`'s own terminal semantics in `PublishingService`.
`FIX_REQUESTED` reopens developer editing with no code change needed here — WS7a already built that guard.

## 5. Errors

| Code | Status | When |
|---|---|---|
| `VERIFICATION_NOT_PENDING` | 409 | Decision posted against a verification that isn't `PENDING` (never submitted, or already decided) |
| `INVALID_FIELD` | 400 | `outcome` not one of `VERIFIED`/`FIX_REQUESTED`/`REJECTED`, or a `checks[].type` not one of `PAN`/`GSTIN`/`PHONE` |

`404` (not a typed API error, `ResponseStatusException` as in `ReviewController`) when `{id}` doesn't exist.

## 6. Testing

Testcontainers integration test `AdminVerificationApiTest`, extending WS7a's `DeveloperVerificationFixtures` test
component to seed a `PENDING` submission (currently only reachable there via direct SQL per WS7a §5 — this slice
is what makes reaching `PENDING` possible through the real submit flow, since WS7a's own tests already cover that
path).

- Queue returns only `PENDING` submissions by default; a `DRAFT` or already-decided row never appears.
- Detail 404s for an unknown id.
- Detail never exposes raw PAN/GSTIN/phone — masked fields only, and a working pre-signed document URL.
- `VERIFIED` decision: `developer_verification.status` → `VERIFIED`, `reviewer_user_id`/`decision_note`/`decided_at`
  set, `verification_check` rows persisted for each submitted check, and — the actual integration point —
  `Org.developerVerifiedAt` becomes non-null. A subsequent BYOK-manifest submit (`PublishingService.submit`) that
  previously 403'd with `BYOK_NOT_VERIFIED` now succeeds, and `SandboxQuotaService` now reads the verified tier's
  limits for that org.
- `REJECTED` decision: status → `REJECTED`; a further `PATCH`/`submit` from the developer still 409s
  (`VERIFICATION_LOCKED`, unchanged WS7a behavior) — verifying `REJECTED` is terminal.
- `FIX_REQUESTED` decision: status → `FIX_REQUESTED`; a further developer `PATCH` now succeeds (WS7a's existing
  guard), and a subsequent `submit` increments `resubmissionCount` (WS7a's existing behavior) — verifying the two
  slices compose correctly end to end.
- Deciding a non-`PENDING` verification (e.g. deciding twice) returns `409 VERIFICATION_NOT_PENDING` and leaves the
  row unchanged.
- Concurrent double-decision race: two simultaneous `decision` calls against the same `PENDING` row — exactly one
  succeeds, the other observes `VERIFICATION_NOT_PENDING` after the row lock releases (same shape as WS7a's
  first-touch race test).
- A non-`ADMIN` caller (a `DEVELOPER` or a `BUYER_ADMIN`) gets `403` on every endpoint in this slice.

`ONTOLOGY.md`: extend the WS7a "Developer verification submission" section with `verification_check`,
`KycProvider`/`ManualKycProvider`, the admin endpoints, and update the existing note that said "reviewerUserId,
decisionNote, decidedAt exist but are unwritten until 7b" / "phone and PAN/GSTIN vendor checks are both deferred to
7b" to reflect that 7b now writes them.

**Manual checks left for the user:** none — this slice has no external dependency (no vendor, no runner, no
browser).

## 7. Inferred decisions for confirmation

| # | Decision | Where |
|---|---|---|
| I1 | Public publisher profile endpoint is split out of WS7b entirely (explicit user choice, 2026-09-28), not merely deferred to a numbered follow-up — it has none yet. | §1 |
| I2 | No new `Org.verificationStatus` column; `developer_verification.status` remains the sole source of truth for verification state, `developer_verified_at` the sole gate flag. | §1, §2 |
| I3 | The decision endpoint is a single `POST .../decision` with an outcome discriminator, not three separate action endpoints, per the WS7a doc's own naming (`POST .../decision`, singular) and to keep the two negative outcomes (`FIX_REQUESTED` vs `REJECTED`) as data rather than as separate routes. | §4 |

## 8. Follow-ups (not built)

- Public publisher profile (`GET /api/v1/publishers/{orgId}`): unscheduled, split out of WS7b.
- Automated `KycProvider` implementations (real PAN/GSTIN/phone vendor checks): unscheduled.
- WS10: bank account / payout capture and payout verification.
- FE-4: the admin console review UI.
