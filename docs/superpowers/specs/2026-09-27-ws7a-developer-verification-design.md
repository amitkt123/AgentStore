# WS7a — Developer Verification: Submission Flow — Design

> **Status:** approved by the user in conversation on 2026-09-27. Narrows the parent's WS7 section in `2026-09-23-agent-store-developer-platform-and-sandbox-design.md`; where the two differ, this document wins. Backend and contract only — no frontend exists in this repo yet; the UI (screens 17/19) is FE-3/FE-4, out of scope here. Decisions that were inferred rather than explicitly stated are marked `[I#]` and listed in §6.

## 1. Goal and scope

A developer org fills out a resumable identity-verification form and submits it for review. WS7 is split into two slices:

- **7a (this document):** data model, developer-facing submission flow (save/resume by step, PAN document upload, submit → `PENDING`).
- **7b:** `KycProvider`/`ManualKycProvider` checks, admin review queue and decision, setting `org.developer_verified_at` and `org.verification_status`, wiring the BYOK/quota gates live, public publisher profile endpoint.

**In scope (7a)**
- `developer_verification` table, one row per org (§2).
- `GET`/`PATCH /api/v1/developer/verification` (§3).
- `POST /api/v1/developer/verification/pan-document` (§3).
- `POST /api/v1/developer/verification/submit` → `PENDING` (§3). No checks run here.

**Out of scope** (with owner)
- `verification_check` table, `KycProvider`, `ManualKycProvider`: 7b.
- Admin queue/decision endpoints (`GET /admin/verifications*`, `POST .../decision`): 7b.
- Setting `org.developer_verified_at` / `org.verification_status`: 7b. Until then every org stays unverified — BYOK (`ByokKeyService`, checked since WS3) and the sandbox quota tier (`SandboxQuotaProperties`, since WS6a) already read `developer_verified_at`; 7a does not touch that code.
- Public publisher profile (`GET /api/v1/publishers/{orgId}`): 7b.
- Bank account / payout capture: deferred to WS10 entirely. The parent spec's bank fields are dropped from the schema, not just unused. [I1]
- Real phone OTP delivery: dropped in favor of manual verification (no SMS vendor — matches the no-paid-third-parties posture already applied to the object store). 7a only captures and encrypts the number; a human confirms it in 7b, recorded as a `verification_check` row then. [I2]
- Automated PAN/GSTIN vendor checks (a KYC API call): 7b builds `KycProvider` as an interface with only `ManualKycProvider` for now, same reasoning. 7a does format-only validation (§3), no vendor call.

## 2. Data model (migration V36)

```sql
CREATE TABLE developer_verification (
    id UUID PRIMARY KEY,
    org_id UUID NOT NULL REFERENCES org(id) UNIQUE,
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',        -- DRAFT, PENDING, VERIFIED, FIX_REQUESTED, REJECTED
    current_step VARCHAR(20) NOT NULL DEFAULT 'ABOUT',  -- ABOUT, TAX_IDS, PHONE, AGREEMENT
    entity_type VARCHAR(20),                            -- INDIVIDUAL, LLP, PRIVATE_LIMITED, PARTNERSHIP, OTHER
    legal_name VARCHAR(255),
    display_name VARCHAR(255),
    country CHAR(2),
    city VARCHAR(100),
    pan_encrypted BYTEA, pan_nonce BYTEA, pan_masked VARCHAR(20),
    gstin_encrypted BYTEA, gstin_nonce BYTEA, gstin_masked VARCHAR(20),
    pan_document_key VARCHAR(512),
    phone_encrypted BYTEA, phone_nonce BYTEA, phone_masked VARCHAR(20),
    agreement_version VARCHAR(20),
    agreement_accepted_at TIMESTAMPTZ,
    resubmission_count INT NOT NULL DEFAULT 0,
    reviewer_user_id UUID REFERENCES app_user(id),
    decision_note TEXT,
    submitted_at TIMESTAMPTZ,
    decided_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

- One row per org (`org_id UNIQUE`), created lazily on first `GET` (§3), not the parent spec's implicit multi-row-per-org shape.
- `pan_encrypted`/`nonce`, `gstin_encrypted`/`nonce`, `phone_encrypted`/`nonce` use the existing `AesGcmCipher` (same key material as `ByokCredential`/`SandboxProfile.fakeSecretsEncrypted`). `*_masked` stores only the last 4 characters, same pattern as `ByokKeyService.last4`.
- `verification_check` is **not created** in this migration — it belongs to 7b, which is the first slice that writes to it.
- `reviewer_user_id`, `decision_note`, `decided_at` are unused by 7a (nothing writes them yet) but the column exists now so 7b needs no further ALTER.
- No `phone_verified_at` column: phone verification is a manual admin check recorded in 7b's `verification_check`, not a timestamp on this row.
- No bank/payout columns at all (§1, [I1]).

## 3. Developer-facing API

All endpoints: `hasRole('DEVELOPER')`, org-scoped from the JWT (`SecurityContextHolder`), same pattern as `LlmKeyController`/`SandboxProfileController`.

**`GET /api/v1/developer/verification`**
Returns the current state. Auto-creates a `DRAFT` row (no separate "start" endpoint) if none exists for the org. Response never includes raw PAN/GSTIN/phone — only `*Masked` fields, `status`, `currentStep`, `entityType`, `legalName`, `displayName`, `country`, `city`, `panDocumentUploaded` (boolean), `agreementVersion`, `agreementAcceptedAt`, `resubmissionCount`, `submittedAt`.

**`PATCH /api/v1/developer/verification`** `{step, fields}`
Validates and saves one step's fields. Step order is fixed: `ABOUT → TAX_IDS → PHONE → AGREEMENT`. `currentStep` advances to the next step in that order when the submitted step's required fields are complete; it never regresses, and a `PATCH` targeting a step earlier than `currentStep` still saves but leaves `currentStep` unchanged. Returns `409 VERIFICATION_LOCKED` unless `status` is `DRAFT` or `FIX_REQUESTED` — no edits while `PENDING` or after `VERIFIED` [I3]. Per-step validation:

| Step | Required fields | Validation |
|---|---|---|
| `ABOUT` | `legalName`, `displayName`, `country`, `city`, `entityType` | non-blank; `entityType` in the enum |
| `TAX_IDS` | `pan`; `gstin` optional | `pan` matches `^[A-Z]{5}[0-9]{4}[A-Z]$`; `gstin`, if present, matches `^[0-9]{2}[A-Z0-9]{13}$` (same shapes `PiiScanner` already uses) — format only, no vendor call |
| `PHONE` | `phone` | matches `^[6-9]\d{9}$` (IN) or `^\+\d{10,15}$` (E.164) |
| `AGREEMENT` | `agreementVersion` | must equal the current constant (`DeveloperAgreement.CURRENT_VERSION = "2.1"`); sets `agreementAcceptedAt = now()` |

`INVALID_FIELD` (400) on a shape/enum failure.

**`POST /api/v1/developer/verification/pan-document`**
Multipart upload, `image/jpeg`, `image/png`, or `application/pdf`, 5 MB max. Stored via `ObjectStorage` (same pattern as `Dataset`/`AgentBundle`), sets `pan_document_key`. `409 VERIFICATION_LOCKED` under the same status rule as `PATCH`. `413`/`DOCUMENT_TOO_LARGE` and `415`/`UNSUPPORTED_DOCUMENT_TYPE` on violation.

**`POST /api/v1/developer/verification/submit`**
Requires `status` in `{DRAFT, FIX_REQUESTED}`, else `409 VERIFICATION_LOCKED`. Requires all of: `ABOUT` complete, `TAX_IDS` has at least `pan`, `PHONE` complete, `AGREEMENT` accepted, `panDocumentKey` present — else `400 VERIFICATION_INCOMPLETE` listing the missing steps. On success: `status = PENDING`, `submittedAt = now()`; if the previous status was `FIX_REQUESTED`, `resubmissionCount += 1`. **Runs no checks** — 7b's admin flow does that.

## 4. Errors

| Code | Status | When |
|---|---|---|
| `VERIFICATION_INCOMPLETE` | 400 | Submit with a required step missing |
| `INVALID_FIELD` | 400 | Bad PAN/GSTIN/phone shape, unknown `entityType`, wrong agreement version |
| `VERIFICATION_LOCKED` | 409 | `PATCH`/document upload while `PENDING`, or after `VERIFIED` |
| `DOCUMENT_TOO_LARGE` | 413 | PAN document over 5 MB |
| `UNSUPPORTED_DOCUMENT_TYPE` | 415 | PAN document not JPEG/PNG/PDF |

## 5. Testing

Testcontainers integration test `DeveloperVerificationApiTest`, with a `DeveloperVerificationFixtures` test component mirroring `SandboxFixtures`/`LlmFixtures`:

- First `GET` auto-creates a `DRAFT` row; a second `GET` returns the same row (no duplicate).
- Step-by-step save and resume across a fresh JWT ("logout"): partial `ABOUT`, then `TAX_IDS`, then `PHONE`, then `AGREEMENT`, each independently persisted.
- PAN/GSTIN/phone format rejection: each bad shape returns `INVALID_FIELD` and does not save.
- Encryption round-trip: response never contains raw PAN/GSTIN/phone (only `*Masked`); direct DB read shows ciphertext + nonce, not plaintext.
- PAN document: oversized and wrong-content-type uploads rejected; a valid upload sets `panDocumentUploaded = true`.
- Submit rejects incomplete steps (`VERIFICATION_INCOMPLETE`), naming which are missing.
- Submit succeeds when all steps + document are present; status becomes `PENDING`; a further `PATCH` or document upload then returns `VERIFICATION_LOCKED`.
- Resubmission: fixture sets `status = FIX_REQUESTED` directly via SQL (7b's decision endpoint doesn't exist yet); submit succeeds and `resubmissionCount` increments.
- Cross-org isolation: another org's JWT never sees or mutates this org's row.
- No unmasked PAN/GSTIN/phone appears in any JSON response across the whole test.

`ONTOLOGY.md`: add `DeveloperVerification` (status/step enums, relationship to `Org`), and note in "Inconsistencies & Unclear Areas" that 7a captures identity but nothing yet sets `developer_verified_at` or `verification_status` — 7b does.

**Manual checks left for the user:** none — this slice has no external dependency (no vendor, no runner, no browser).

## 6. Inferred decisions for confirmation

| # | Decision | Where |
|---|---|---|
| I1 | Bank/payout fields dropped from the schema entirely, not merely unused; WS10 owns adding them when it exists. | §1, §2 |
| I2 | Phone verification is manual (admin eyeballs the captured number in 7b); no OTP delivery is ever built for v1. | §1, §3 |
| I3 | No editing after submission — a developer cannot withdraw a `PENDING` submission to edit it; only `FIX_REQUESTED` (7b's outcome) reopens editing. | §3 |

## 7. Follow-ups (not built)

- 7b: `verification_check`, `KycProvider`/`ManualKycProvider`, admin queue/decision, `org.developer_verified_at`/`verification_status`, publisher profile.
- WS10: bank account / payout capture and payout verification.
- FE-3/FE-4: the five-step form UI and admin console UI.
