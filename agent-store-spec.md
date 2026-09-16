# Agent Store — Project Spec

> Consolidated from planning discussions. Use this as context when building — it captures the decisions made and the reasoning behind them, not just a feature list.

## 1. Core thesis

This is not an App Store clone. An app store distributes software with near-zero marginal cost. This platform distributes **labor** — every run has real marginal cost (inference, tool calls) and real liability (the agent acts on someone's behalf, sometimes with money or a government portal). That difference drives every model below.

**What's actually being sold, in order of what buyers pay for:**
1. **Settlement** — metered runs, disputes, refunds, one consolidated invoice
2. **Governance** — declared permissions, full audit trail per run, human approval gates on anything that writes/pays/messages
3. **Discovery** — catalog browsing, comes last. Discovery is nearly free now; anyone can find an agent on GitHub.

**Target buyer: B2B, not retail.** Confirmed decision — the agent-store model doesn't work well for consumers; target Indian SMBs, CA/compliance firms, and eventually enterprise finance teams instead.

**Competitive angle:** Salesforce AgentExchange, OpenAI's GPT store, and AWS Marketplace already run captive stores. A neutral, model-agnostic store wins on things they structurally can't offer: cross-platform governance, true outcome-based billing, or deep ownership of one vertical (Indian fintech/SMB compliance) where the payment rails and regulatory surface are known cold. Pick the vertical and the billing layer first — the general store comes later, if ever.

---

## 2. Economic model

**Pricing units** (support several — the right one depends on the agent):
- Metered per run (tokens + tool calls + compute) — default for most agents
- Per outcome (per qualified lead, per resolved ticket, per reconciled invoice) — most defensible, and what buyers increasingly expect
- Seat/subscription for always-on agents
- Bounty/job-based for future agent-to-agent work

**Who pays for inference — the key fork:**
- *Developer BYOK*: platform is pure distribution; thin margin; a 30%-style take rate won't survive.
- *Platform-provided inference*: platform buys models wholesale, bundles into runs, earns inference margin **and** platform fee. More capital risk, much better economics, and it's what makes the take rate survivable. **Chosen direction.**

**Revenue stack:**
| Stream | Notes |
|---|---|
| First-party agent revenue | ~100% of revenue in year one. Model the business as this first, not as the marketplace |
| Platform take rate | 10–15% of GTV on third-party agents (not 30% — the product has real COGS) |
| Inference margin | Only works because inference is platform-owned, not BYOK |
| Developer tiers | Verified badge, priority placement, private/enterprise listing, larger eval/sandbox quota |
| Enterprise contracts | One MSA, SSO, audit export, consolidated billing, SLA — highest ACV, slowest sale |
| Setup/integration fees | Tally connector installs, data migration, CA-channel onboarding — unglamorous but real, and buys distribution through CAs |

**Wallets:** prepaid, to manage credit risk and chargeback exposure. Keep these as **non-refundable service credits, not stored value** — an open-redemption prepaid balance drifts into RBI PPI licensing territory, which is a fight to avoid in year one.

**Cold start strategy:** seed supply first — build/commission agents yourself for 20–30 high-frequency, easily-measured jobs — before opening the developer side at all.

---

## 3. Technical model

**Packaging standard (the "Android permissions model" for agents):**
- Manifest declares capabilities, required tool/MCP scopes, OAuth scopes, network egress allowlist
- Model requirements + a declared cost envelope per run
- Evals ship with the agent; platform re-runs them in its own pipeline
- Version + changelog, staged rollout, instant rollback
- MCP for tool access; A2A-style agent card for discovery, so the catalog is machine-readable (agent-to-agent commerce is the second-order market)

**Runtime:**
- Hosted, isolated execution per run (microVM / gVisor — deferred until third-party code exists, see §7)
- Secrets injected from a vault, scoped short-lived credentials only
- Egress limited to exactly what the manifest declares
- Never let an agent hold long-lived user tokens

**Metering and settlement — this is the moat, and Amit's own background (payments/distributed systems) maps directly onto it:**
- Every tool call and model call emits a usage event → Kafka → idempotent aggregation → double-entry ledger (BigDecimal, tracking billing and actual cost as separate columns)
- Per-run cost caps + kill switch (a runaway agent is a runaway bill)
- Full trace per run for disputes, refunds, and outcome verification
- Payouts to developers with GST/TDS handled properly; UPI + cards for consumer/SMB wallets

**Trust pipeline:** automated eval + red-team on submission → behavioral monitoring at runtime (tool-call anomalies, prompt-injection attempts) → reputation from completed runs and dispute rate, not star ratings.

**Compliance:** DPDP Act 2023 consent and data handling, tenant isolation, per-run audit trail, India data residency.

---

## 4. Deployment topology

**Client surfaces:**
- **Primary: responsive web app, desktop-first.** The buyer (finance/accounts staff) works at a desk with Tally open; reconciliation tables, run traces, and approval queues are wide-table work that's bad on mobile. All core surfaces live here: catalog, connections hub, run console, wallet, invoices, developer portal.
- **Secondary: WhatsApp, as a notification/approval channel — not a second console.** The SMB *owner* (who approves and pays) lives in WhatsApp, not a dashboard: "12 mismatches found, ₹1.4L ITC at risk — view," "Approve sending 8 reminders? Yes/No." Nearly free since the BSP integration is being built for the collections agent anyway. Do not try to run the full product through it.
- **Tertiary: thin on-premise connector.** Tally runs on the customer's Windows desktop/LAN with no cloud API. Ship a small Windows service that talks to Tally locally (XML/ODBC) and holds an **outbound-only** connection to the platform — nothing for the customer's IT to open inbound. Annoying to build, which is exactly why it's defensible against pure-SaaS competitors.
- **Not in v1: native mobile app.** If mobile is wanted, ship the web app as a PWA. A native app only earns its place once reliable push matters, and WhatsApp is already covering that.
- **Developer surface:** CLI + docs portal + web dashboard for analytics/payouts. No browser IDE.

**Server topology:**
- Single India cloud region (AWS ap-south-1 Mumbai primary, ap-south-2 Hyderabad DR) — non-negotiable given GST data, bank statements, and Aadhaar-consented KYC under DPDP; also removes an entire class of enterprise sales objection.
- **Control plane** (catalog, tenants, wallet, ledger, credentials vault) — trusted, boring, never runs untrusted code.
- **Execution plane** (per-run agent execution) — treated as hostile: no long-lived tokens, secrets injected per run, egress restricted to manifest allowlist, hard cost cap + kill switch per run.
- Multi-tenancy: shared control plane with row-level tenant isolation + per-tenant encryption keys for stored documents; isolated compute per run. Single-tenant VPC deployment is a later enterprise upsell, not a v1 architecture.

**v1 build reality check:** all five seed agents are first-party (trusted code written in-house), so full microVM-per-run isolation solves a problem that doesn't exist yet. Build v1 as a modular Spring Boot monolith — catalog, runs, metering, ledger, connections as clean module boundaries — on Postgres + Redis + Kafka + object storage, on one small EKS/ECS cluster. Design module boundaries so the execution plane can be extracted later as a deployment change, not a rewrite — but don't pay for Firecracker-level isolation until a third party's code is actually running.

**The one thing not to defer:** the ledger and metering path. Hardest to retrofit, everything else depends on it, and it's the part where the payments/distributed-systems background pays off immediately.

---

## 5. Feature set

**Buyer side:**
- Catalog browsable by job-to-be-done, priced by outcome unit (₹/reconciled invoice, ₹/verified vendor) instead of star ratings
- Agent detail page: declared permissions, data touched, eval scorecard, cost envelope, a real sample trace
- Sandbox trial on synthetic/uploaded sample data before any credential is connected
- Connections hub: grant/revoke Tally, GST portal, bank/AA, WhatsApp, Gmail — scoped, expiring, revocable per agent
- Run console: queue, live trace, **approval gates** on anything that writes to a government portal, moves money, or messages a customer (non-negotiable for finance buyers)
- Prepaid wallet + subscriptions, per-agent spend caps, auto top-up
- Maker-checker roles and team permissions
- Usage statements with per-run line items + proper GST invoice
- Dispute button attached to a specific run ID

**Developer side:**
- Manifest-based packaging + SDK + local test harness
- Mandatory eval suite shipped with the agent, re-run by the platform
- Versioning, staged rollout, instant rollback
- Analytics: runs, success rate, dispute rate, cost-per-run vs. price (so developers see their own margin)
- Payouts dashboard with GST/TDS handled
- Option to publish privately to a single enterprise tenant

**Platform/trust layer:**
- Isolated per-run execution, vault-injected secrets, manifest-based egress allowlist
- Per-run cost cap + kill switch
- Kafka usage events → idempotent aggregation → double-entry BigDecimal ledger (billing and cost tracked separately)
- Runtime behavioral monitoring (tool-call anomalies, injection attempts, scope escape)
- DPDP consent records, tenant isolation, India data residency

---

## 6. Publishing pipeline (how a developer lists an agent)

**Three publisher tracks — not one:**
1. First-party (built in-house)
2. Verified partner (CA firms, agencies, ISVs — invite-only)
3. Open submission — **explicitly deferred, should not exist in v1.** Supply quality is the only differentiator against captive stores from Salesforce/AWS/OpenAI; opening the gates early destroys it.

**Pipeline for a listing:**
1. **Developer onboarding** — KYB the publisher (PAN + GSTIN + bank penny-drop) — reuses the platform's own KYC/KYB agent
2. **Build + declare** — SDK-based build, manifest declares scopes, cost envelope, pricing unit, data classes touched
3. **Ship evals** — minimum N golden cases with expected outputs; no evals, no listing
4. **Automated gate** — manifest lint, dependency/secret scan, eval replay in sandbox, red-team suite (injection, exfiltration, scope escape), cost profiling to check the declared envelope is honest
5. **Tiered human review** — Tier A (read-only, produces a draft for a human) auto-approves on green; Tier B (writes to portals, moves money, messages customers) always gets manual review
6. **Pricing approval** — platform approves the pricing *unit*, not just the number, since it owns metering/settlement; tax category assigned here
7. **Staged rollout** — 5% → 25% → 100%, auto-halted on error or dispute-rate breach
8. **Post-listing** — reputation from completed runs and disputes; payouts on T+dispute-window; published deprecation policy

---

## 7. The first five agents to build

Chosen to share infrastructure, avoid all being blocked on the same external dependency, and sit where existing domain depth (payments, KYC/KYB, distributed systems) already applies.

1. **Bank + UPI reconciliation agent** — matches bank/UPI settlement files against invoices/books; flags unmatched, short-settled, duplicate credits. ₹2/transaction matched, ₹999/mo. *Build first — no external licensing gate, and this is squarely existing domain expertise.*
2. **WhatsApp invoicing + collections agent** — sends invoices, chases overdue payments on schedule, posts a UPI link, marks paid. ₹5/invoice-cycle, ₹1,299/mo. *Needs a WhatsApp BSP (weeks, not months). Outcome is trivially provable — DSO drop.*
3. **KYC/KYB verification agent** — PAN + GSTIN + Aadhaar-consented + bank penny-drop → verification report. ₹12/verification, ₹1,499/mo (500 checks). *Directly overlaps with existing kyc-kym work; also becomes the internal identity primitive used to onboard the platform's own publishers/merchants.*
4. **GST purchase reconciliation agent** — GSTR-2B vs. purchase register, flags mismatches and blocked ITC pre-filing. ₹3/invoice reconciled, ₹1,999/mo. *Sharpest pain, clearest ROI — but gated on GSP/ASP onboarding. Start that paperwork immediately, before any agent code.*
5. **Vendor GST compliance monitor** — watches vendor filing status, warns before ITC gets blocked. ₹5/vendor-month, ₹999/mo. *Rides the same GSP integration as #4 for near-zero incremental build cost; subscription-native rather than seasonal.*

**Through-line:** #1, #4, #5 all feed one shared reconciliation engine. #3 is the identity primitive the platform needs internally regardless. #2 is the one that establishes a WhatsApp channel reused for every future agent's notifications.

**Deliberately excluded from the first five:** ITR prep (seasonal — can't validate retention), contract review (hard to price per outcome), anything consumer-facing.

**Critical path flag:** GSP onboarding gates agents #1 (partially), #4, and #5 — it cannot be compressed and should begin before any agent code is written.

---

## 8. Full 20-agent seed catalog (for reference, post-launch expansion)

**Tier 1 — SMB/MSME back office** (agents 1–6): GST reconciliation, e-invoice/e-way bill generation, WhatsApp invoicing/collections, GST notice response, bank/UPI reconciliation, Tally/Zoho data entry.

**Tier 2 — Sales and support** (7–11): Lead qualification (₹8/qualified lead, ₹1,999/mo), support triage/resolution (₹4/resolved ticket, ₹1,499/mo), appointment/booking (₹3/booking, ₹599/mo), resume screening + interview scheduling (₹5/candidate, ₹2,499/mo), outbound follow-up (₹6/lead-sequence, ₹1,499/mo).

**Tier 3 — Content and commerce** (12–14): Product listing agent (₹10/listing, ₹999/mo for 200 listings), social content agent (₹15/post-set, ₹799/mo), ad copy/campaign agent (₹25/campaign, ₹1,299/mo).

**Tier 4 — Professional/compliance** (15–18): ITR preparation assistant (₹149/return, ₹2,999/season CA bulk plan), contract review agent (₹199/contract, ₹1,999/mo), KYC/KYB verification (see §7), vendor compliance monitor (see §7).

**Tier 5 — Personal/prosumer** (19–20): Document/form filler (₹19/form, ₹199/mo), bill negotiation + subscription auditor (free scan + 15% of first-year savings, ₹99/mo).

Sequencing note: don't launch all 20 at once — anchor on Tier 1, where the pain is sharpest and outcomes are trivially measurable (mismatches found, invoices collected, notices answered), proving ROI within one billing cycle before expanding.

---

## 9. Open items / not yet decided

- Exact developer take-rate tiering beyond the 10–15% band
- Whether/when to open verified-partner track vs. staying first-party-only for longer
- Metering/ledger schema — flagged as the next thing to design, not yet specified
- Tally connector protocol — flagged as next, not yet specified
- Whether a native mobile app is ever built, or the PWA is permanent
