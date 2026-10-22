# Frontend gaps

Mockup elements omitted because the backend has no data or route for them. Update this file whenever a phase omits something.

| Id | Mockup element | Why omitted |
|---|---|---|
| G1 | Search and "Ask about this run" (⌘K) | No search or run-question backend |
| G2 | Notification bell and feed | Waits on WS11 |
| G3 | "Keep it private", "Review the request" on a file | No such dataset action found in `DatasetController` |
| G4 | Approver list and "Ask approvers on" | No approver-assignment or notification-channel endpoint found |
| G5 | Monthly limit per agent | Only per-run `spendCap` exists on a connection |
| G6 | Replay run from the buyer side | Rerun is a sandbox route and needs an ownership check |
| G7 | Test cases passed, attack probes, runs and dispute rate, typical cost | Waits on WS8 |
| G8 | Try on sample data | No backend |
| G9 | Version pinning and "1 new" update badge | Waits on WS9 |
| G10 | Sign-in with Google | Backend supports it (POST /auth/google) but it needs a Google client id, which is not configured |
| G11 | Block descriptions on the live run | Run detail has no agentId/versionId to fetch the manifest |
| G12 | "Code we sent by SMS" on the Phone step | WS7a captures the number and a person at Karya confirms it; no SMS vendor (no paid third parties) |
| G13 | "Payout account" step | Bank fields are deferred to WS10c, so the flow is four steps, not five |
| G14 | Reviewer's note on a fix-requested or rejected verification | `DeveloperVerification.decisionNote` is stored but `DeveloperVerificationView` does not return it. The UI reads an optional `decisionNote` and falls back to a plain sentence |
| G15 | "What changes when you're verified" comparison panel | Not in the P3a spec; limits shown in it (sandbox minutes, builds a day) have no backend read endpoint |
| G16 | Studio "what it did" half and flow canvas | Need sandbox run endpoints that are not on master; deferred to P3c. The Studio shows a note instead |
| G17 | Validator and submit-failure reasons | `POST /agents/{a}/versions` 400 and `POST …/submit` 403/409 carry no message (the app hides `ResponseStatusException` reasons). The UI shows fixed sentences per status; a `{code, message}` body from the backend would let it show the real reason |
| G18 | "What this patch changed" | `ManifestPatchResponse.diff` is computed against the LIVE version, not the previous draft, so the UI builds its confirmation from the request. A diff against the prior draft would let it show real before/after |
| G19 | Billing "Add money", "Bank transfer details", top-up history, "Get money back" | Resolved in WS10b |
| G20 | Billing bills list and "Pay" in postpaid mode | Resolved in WS10b |
| G21 | Earnings "Withdraw", "Payout account", withdrawal history | No payout backend; arrives with WS10c |
| G22 | Tax invoices on Billing | GST tax invoices are a WS10 non-goal (legal question, spec §8 Q6); the postpaid bill in WS10b is a statement, not a tax invoice |

## Known visual differences
- Files "Added" shows a time only; a date is needed for files older than today.
- Runs history "Started" shows a time only for the same reason.
- Live run block descriptions are missing (G11).
- Get verified has two panels (steps and form); the mockup's third "What changes when you're verified" panel is omitted (G15).
- The Studio has no flow canvas and no observed-behaviour half (G16).
