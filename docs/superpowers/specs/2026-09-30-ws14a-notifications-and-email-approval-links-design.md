# WS14a: Notifications and email approval links, design

## Goal and scope

An approver hears about a gate within about a minute and can approve or reject it from a phone with a one-tap emailed link. Other run events land in an in-app notification centre, with per-person preferences and quiet hours.

In scope: a `notifications` outbox consumer, the notification centre and preferences API, quiet hours, an email delivery job, single-use approval links with public GET/POST endpoints.

Out of scope: Slack (WS14b), the weekly digest, `MORE_ACCESS_REQUESTED` events (needs WS11 consent events), the `approval_links_require_login` org setting, per-IP rate limits on the public endpoints (WS12), and frontend screens (FE-3).

This re-bases `docs/superpowers/plans/2026-09-23-ws14-notifications-and-approval-links.md`, which assumed a `GateDecisionService`, `ApiProblemException`, `Connection.notifyChannels` and migrations V26 to V29. None of those exist; the outbox and the database-checked `GateService.decide` came from WS15b (PR #30). Migrations continue from V42.

## Architecture

The consumer only writes `notification` rows, in the recipient's workspace. Each row records whether an email is owed and when it is due. A ShedLock-guarded job sends what is due and mints the approval link just before sending. Sending never happens inside the outbox transaction, so a slow mail server cannot stall the dispatcher, and a quiet-hours hold is just a later due time.

## Data

`V42__notification.sql`
- `notification`: `id`, `user_id`, `org_id`, `category`, `title`, `body`, `run_id`, `gate_id` (nullable), `source_event_id`, `created_at`, `read_at`, `resolved_at`, `email_due_at`, `email_sent_at`, `email_attempts`, `email_error`, `email_skipped`, `email_no_link`. Unique on `(user_id, source_event_id)`, which makes the consumer idempotent under redelivery.
- `notification_preference`: `(user_id, category)` with `web` and `email` booleans. Missing rows fall back to the defaults below.
- Quiet hours columns on `app_user`: `quiet_enabled` (default false), `quiet_start` (20:00), `quiet_end` (08:00), `quiet_tz` (`Asia/Kolkata`).
- `run.started_by_user_id` (nullable): the buyer user who started a production run, set by `RunController.trigger` and `rerun`. A run with no starter produces no `RUN_FINISHED` or `RUN_STOPPED_EARLY` notification.

`V43__approval_link.sql`
- `approval_link`: `id`, `notification_id`, `user_id`, `gate_id`, `token_hash` (unique), `expires_at`, `used_at`, `decided_ip`, `decided_user_agent`.

Categories produced now: `APPROVAL_NEEDED`, `RUN_FINISHED`, `RUN_STOPPED_EARLY`. `SPEND_LIMITS` and `WEEKLY_SUMMARY` are accepted in preferences so the screen works but nothing produces them yet.

Default channels (web / email): `APPROVAL_NEEDED` on/on, `RUN_STOPPED_EARLY` on/on, `RUN_FINISHED` on/off, `SPEND_LIMITS` on/on, `WEEKLY_SUMMARY` off/on.

## Consumer `notifications`

Handles `GATE_OPENED`, `RUN_FINISHED`, `GATE_DECIDED`, `GATE_EXPIRED`. It never sends email.

- `GATE_OPENED`: recipients are the payload's `eligibleApproverIds`. When that is null (a buyer org with no approver members), recipients are the org's `BUYER_ADMIN` members and the row is flagged `email_no_link`. Category `APPROVAL_NEEDED`; `email_due_at` is now when the preference allows email, ignoring quiet hours.
- `RUN_FINISHED`: recipient is `run.started_by_user_id`; a null starter notifies nobody. COMPLETED gives `RUN_FINISHED`; a stopped-early status gives `RUN_STOPPED_EARLY`. `email_due_at` is `QuietHours.nextAllowed(...)` when email is on.
- `GATE_DECIDED`, `GATE_EXPIRED`: set `resolved_at` on the matching `APPROVAL_NEEDED` rows. Nobody is notified.
- A user whose web and email preferences for the category are both off gets no row, except `APPROVAL_NEEDED`, whose web channel is always on.

## Email delivery job

`EmailDeliveryJob`: ShedLock name `emailDelivery`, gated on `agentstore.jobs.enabled`, interval `agentstore.notifications.email-interval` (default `PT30S`).

Each pass claims one due row at a time in a short transaction (`email_due_at <= now() and email_sent_at is null and not email_skipped`, `FOR UPDATE SKIP LOCKED`), builds the message, calls `EmailSender.send`, then sets `email_sent_at`.

- Delivery is at least once: a crash between send and commit can repeat one email.
- A send failure increments `email_attempts`, records `email_error` and pushes `email_due_at` out by a short delay. After 5 attempts the row is left alone.
- For an `APPROVAL_NEEDED` row with a gate, the job first re-checks the gate is still PENDING. If not, it sets `email_skipped` and sends nothing.
- A row flagged `email_no_link` gets an email with no link, telling the admin to add an approver or decide in the web app.

## Approval links

Minted by the job just before the send: 32 random bytes from `TokenHasher.newToken()`, stored only as SHA-256. One per approval email. `expires_at` is the gate's own `expires_at`. The link binds to the gate id.

Public endpoints under `/api/v1/approvals/**`, added to the `permitAll` list in `SecurityConfig`:

- `GET /{token}` returns the card (run, action, summary, expiry). It never changes state, so a mail scanner or link preview cannot approve anything.
- `POST /{token}/decision` with `{decision, note}`. In one transaction: claim the link with `update approval_link set used_at = now() where id = ? and used_at is null`, confirm the gate is the linked gate and still PENDING, then call `GateService.decide(..., DecisionChannel.EMAIL_LINK)` as the link's user and record IP and user agent.
- Errors: unknown token 404; used, expired, or gate no longer PENDING 410; `decide` refusing (for example `NOT_AN_APPROVER` for a member removed after the email went out) 403 with the transaction rolled back, so the link stays unused.

## Notification centre and preferences API

JWT-authenticated. A person only ever sees their own rows in their current workspace.

- `GET /api/v1/notifications?filter=needs_you|agents|money&unread=true&limit=` newest first. `needs_you` is `APPROVAL_NEEDED`; `agents` is `RUN_FINISHED` and `RUN_STOPPED_EARLY`; `money` is `SPEND_LIMITS`.
- `GET /api/v1/notifications/unread-count`, `POST /api/v1/notifications/{id}/read`, `POST /api/v1/notifications/read-all`. The unread count is not on `/me`, so identity does not depend on notifications.
- `GET` and `PUT /api/v1/notification-preferences`: per-category `web` and `email`, plus quiet hours (`enabled`, `start`, `end`, `timezone`). `APPROVAL_NEEDED` web cannot be turned off, and quiet hours never hold its email. An invalid timezone or `start` equal to `end` is 400.

## Quiet hours

`QuietHours.nextAllowed(now, start, end, zone)` is a pure function. Windows may wrap midnight. Outside the window it returns `now`; inside it returns the window's end. Applies to every category except `APPROVAL_NEEDED`.

## Configuration

`agentstore.notifications.email-interval` (default `PT30S`), `agentstore.web.base-url` (existing setting; base for links in emails, `{base}/approve/{token}`). The job also honours `agentstore.jobs.enabled`; tests disable it and call the job directly.

## Testing

- Link: a GET leaves the gate PENDING; a link whose gate was since approved in the web app, rejected, expired, or replaced by a second gate on the same run answers 410 and decides nothing; a double POST decides once and the second call is 410 with no second decision or step; an approver removed after the email went out gets 403 and the link stays unused.
- Quiet hours: a run-finished email during quiet hours is held until the window ends while an approval email at the same moment is due immediately; `QuietHours` unit tests cover wrapping windows.
- Consumer: recipients from the snapshot; admin fallback with `email_no_link`; a redelivered event creates no duplicate rows; `GATE_DECIDED` and `GATE_EXPIRED` resolve the matching rows.
- Delivery job: the skipped email when the gate was already resolved; retry after a send failure and the stop after 5 attempts; no repeat send once `email_sent_at` is set.
- API: per-user isolation of the centre; preference validation; `APPROVAL_NEEDED` web cannot be disabled.

## Known limitations

- A crash between send and commit can repeat one email (at-least-once).
- The public approval endpoints have no rate limit until WS12.
- Retention of `notification` and `approval_link` rows is not handled; it joins the outbox retention follow-up in `docs/known-issues.md`.
