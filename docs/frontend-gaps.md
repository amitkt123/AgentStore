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
