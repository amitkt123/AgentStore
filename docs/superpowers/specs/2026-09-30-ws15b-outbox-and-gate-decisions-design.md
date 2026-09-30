# WS15b: transactional outbox and database-checked gate decisions

## Purpose

WS14 (notifications and approval links) needs two things the code lacks: a durable event trail that survives a crash, and a gate decision that re-checks the decider against the database. WS15b builds both and nothing else. The rest of the original WS15 plan (org monthly limit, spend alerts, activity feed, workspace home, search) is deferred to WS15c.

Success: a gate opening publishes an event in the same transaction; a registered consumer receives it exactly once (idempotently, after retries); a decider who is no longer an approver gets 403.

## Current state

- `GateService` opens and decides gates and snapshots eligible approvers (`EligibleApprovers`) when a gate opens.
- In-process Spring events exist: `GateOutcomeEvent` (published by `GateService.decide` and `RunLifecycleService` on expiry) and `RunFinishedEvent`. `OutputCommitService` and `RunReportService` consume them and need the publishing transaction. They stay as they are.
- No event is published when a gate opens.
- `GateService.decide` trusts the token role (`@PreAuthorize BUYER_APPROVER`) plus the snapshot. A member removed within the 60-minute token life can still decide.
- `decidedVia` is hardcoded to `WEB`; the enum already has `EMAIL_LINK` and `SLACK_LINK`.
- Latest migration is V40; this work adds V41.

## Design

### Tables (V41)

- `domain_event(id uuid pk, type text, org_id uuid null, aggregate_id uuid, payload jsonb, created_at timestamptz)`. `org_id` is null for events with no buyer org (sandbox runs).
- `domain_event_delivery(id uuid pk, event_id fk, consumer text, status text, attempts int, next_attempt_at timestamptz, last_error text)`. Unique on (event_id, consumer). Status is PENDING, DONE or DEAD. Index on (status, next_attempt_at).

### Components (package `com.agentstore.platform.events`)

- `DomainEventPublisher.publish(type, orgId, aggregateId, payload)`. `@Transactional(propagation = MANDATORY)`: it fails if called outside a transaction. It inserts the event and one PENDING delivery per registered consumer that `handles(type)`.
- `DomainEventConsumer`: `String name()`, `boolean handles(String type)`, `void handle(DomainEvent event)`. Consumers must be idempotent, because a delivery can repeat after a crash.
- `OutboxDispatcher.dispatchOnce()`. Claims due PENDING deliveries with `FOR UPDATE SKIP LOCKED`, in small batches. Each delivery runs in its own transaction: call the consumer, mark DONE. On an exception, increment attempts, store `last_error`, and set `next_attempt_at` with exponential backoff (30s, doubling, capped at 1h). After 10 attempts the row is marked DEAD and no longer retried.
- `OutboxDispatcherJob`: a ShedLock-guarded scheduled job, gated on `agentstore.jobs.enabled` exactly like `RunReaperJob`. Tests disable the job and call `dispatchOnce()`.

### Events published

| Type | Where | Payload |
|---|---|---|
| `GATE_OPENED` | end of `GateService.open` | runId, gateId, action, summary, expiresAt, eligibleApproverIds |
| `GATE_DECIDED` | `GateService.decide` | runId, gateId, decision, decidedBy, channel |
| `GATE_EXPIRED` | gate expiry path in `RunLifecycleService` | runId, gateId |
| `RUN_FINISHED` | `RunLifecycleService.finish` | runId, status, terminalReason |

Each is published in the same transaction as the state change, so a rollback removes the event. The in-process Spring events are untouched.

### Gate decisions

`GateService.decide` gains a `DecisionChannel` parameter (`WEB` today; `EMAIL_LINK` and `SLACK_LINK` are used by WS14; it maps onto the existing `DecidedVia`). Before changing state, and inside the run-row lock, it checks the database: the decider must hold a current `org_membership` in the run's buyer org with role `BUYER_APPROVER`, and, when the gate has an approver snapshot, must be on it. Failure is 403 with code `NOT_AN_APPROVER`. Sandbox runs (no buyer org) keep today's behaviour. The controller endpoints pass `WEB`.

## Out of scope

- The real notification consumer (WS14). No production consumer ships here; a test consumer proves the mechanics.
- Activity feed, org monthly limit, spend alerts, home endpoint, search (WS15c).
- Event retention or cleanup of DONE rows (noted for later).

## Testing

- An event and its deliveries are written in the publishing transaction and vanish on rollback; publishing outside a transaction throws.
- A consumer receives each event exactly once; a consumer that does not handle the type gets no delivery row.
- A failing consumer is retried with backoff, then marked DEAD after 10 attempts.
- Two dispatchers running concurrently never claim the same delivery.
- `GATE_OPENED`, `GATE_DECIDED`, `GATE_EXPIRED` and `RUN_FINISHED` are each emitted from the real flow.
- A removed member, a member of another org, and a user not on the snapshot each get 403 on decide; `decidedVia` records the channel.
- Existing gate, run and data-plane tests still pass.
