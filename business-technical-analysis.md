# Agent Store — Business & Technical Analysis

> Companion to `agent-store-spec.md`. This document stress-tests the spec's assumptions against the current (September 2026) competitive, regulatory, and technical landscape, and against the three product mockups (Karya: buyer console, developer portal, connection flow). It does not replace the spec — it flags what to keep, what to revise, and what needs a decision before agent #1 ships.

---

## Bottom line

The core thesis — sell settlement and governance, not discovery, to a regulatory vertical you know cold — is still the right bet, and three findings make it *stronger* than when the spec was written: the MCP/A2A protocol bet has converged into an industry standard ahead of schedule, every hyperscaler agent store is captive and none do real outcome-based billing, and 2025–2026 funding data shows governance-flavored agent companies raising while generic automation companies (Relay) are shutting down. The mockups already demonstrate the hardest part — a permission statement that reads as a legal document, not a settings page — consistently across buyer, developer, and connection flows.

But four assumptions need correction before you build against them, in order of how expensive they are to discover late:

1. **The wallet's legal safety is not confirmed.** RBI's 2026 draft PPI rules define prepaid instruments functionally, with no carve-out for "non-refundable service credits." This needs a lawyer's opinion before the ledger is built, not after.
2. **GSP access is a partnership to negotiate, not paperwork to file.** First-batch GSP capital requirements (₹5cr+ paid-up capital) put direct GSTN access out of reach for a pre-revenue company. Agents #1, #4, #5 need a GSP partner in the unit economics, not a line item that says "start the paperwork."
3. **Agent #1 and #4 are not open water.** Flick AI, GSTHero/Covoro, LEDGERS.Cloud, and Hisabkitab are already live in 2026 with AI-native GST/bank reconciliation. Worse, Tally itself is shipping native AI reconciliation in TallyPrime 7.1 — the chokepoint vendor the "Tally connector as moat" argument depends on is building the same capability.
4. **The WhatsApp collections agent's pricing breaks in about two weeks.** Meta starts charging per-message for service/utility messages on Oct 1, 2026. ₹5/invoice-cycle was priced against a free-service-message assumption that expires almost exactly at launch.

None of these kill the plan. All of them change what "build first" should mean for the next 60 days.

---

## 1. Business prospect

### 1.1 Competitive landscape — the whitespace is real, but it's one layer higher than you're describing

Every major platform now runs an agent marketplace, and the pattern is uniform:

| Platform | Model | Governance | Billing | Captive? |
|---|---|---|---|---|
| Salesforce AgentExchange | Unified AppExchange+Slack+Agentforce, 2026 | Mandatory security review, admin data boundaries, runtime audit extending | 15% (ISV) / 25% (OEM) take rate | Yes — Agentforce runtime only |
| OpenAI GPT/Apps Store | 4M+ developers | Minimal — no enterprise-grade permission/audit layer | No live in-app revenue share as of mid-2026; builders report near-zero monetization | Yes — OpenAI models only |
| Microsoft Copilot / Agent 365 | Agent 365 GA May 2026, $15/user/mo | Entra + Purview identity/audit — most enterprise-grade of the captive players | Copilot Credits, $0.01 each | Yes — M365/Azure |
| AWS Marketplace | Dedicated "AI Agents & Tools" category | AWS-native IAM/audit | Consumption | Yes — Bedrock runtime, but open to any seller |
| Google Gemini Enterprise (ex-Agentspace) | Agent Gallery: 0→70+ agents in 12 months, $750M partner fund | — | Consumption (per vCPU-hour) | Yes — but co-authored A2A, now neutral-governed |
| ServiceNow | AI Agents category, Jan 2026 | — | License+consumption; CEO has publicly rejected outcome-based pricing | Yes |

**None of the six offer real outcome-based billing**, despite ServiceNow's own leadership admitting buyers ask for it. That confirms §2's fork (platform-provided inference, outcome pricing) as a genuine differentiator, not a hypothesis. The bigger tell is Google's $750M partner fund and Salesforce's aggressive re-launch: well-capitalized incumbents are racing to win on *catalog scale*, which is exactly the layer the spec correctly deprioritizes ("discovery comes last"). That's the right call, but it means the plan is not competing for the same prize as the giants — it's building underneath them, in a place they've structurally chosen not to go (neutral, model-agnostic, vertical-deep). Say that explicitly in fundraising or partner conversations: you are not trying to out-catalog Salesforce.

Funding data backs the governance-first bet specifically: agentic AI funding exceeded $1B in H1 2026 (vs. $538M H1 2025), but it's concentrating in governance/compliance-flavored names (Norm AI's $120M Series C) while undifferentiated workflow-automation plays are dying (Relay shut down Sept 14, 2026, citing token costs and slow enterprise sales). The spec's insistence on settlement+governance-as-product rather than discovery-as-product is aligned with where capital is actually flowing.

### 1.2 The vertical itself is not empty — revise the "no external dependency, build first" framing

§7 frames the bank/UPI reconciliation agent as the safe starting point ("no external licensing gate... squarely existing domain expertise") and the GST reconciliation agent as gated only by GSP paperwork. Current competition changes both:

- **Flick AI** — AI-native, live in 2026, does invoice parsing + bank reconciliation + GST/TDS reconciliation at ₹4,999/yr flat. Directly overlaps agents #1 and #4.
- **GSTHero (as Covoro)** — explicitly marketing "agentic AI" for GST automation.
- **ClearTax and Zoho (Zia)** — both adding AI-assisted GST reconciliation as features inside existing suites they already dominate.
- **LEDGERS.Cloud, Hisabkitab** — both marketing "AI agents" for SME bookkeeping/GST in 2026.
- **Tally itself (TallyPrime 7.1)** — built-in AI chat assistant, connected banking, GST/HSN validation. This is the most structurally important finding: the platform the spec's own connector strategy depends on is building native reconciliation intelligence. If Tally closes the gap, the "annoying to build, which is exactly why it's defensible" argument for the on-prem connector (§4) weakens, because the thing you're defending access to is also building the feature you're selling.

This doesn't invalidate agents #1 and #4 — it changes what has to be true for them to win. **None of the competitors found use true outcome/per-invoice pricing; all are flat subscription or tiered.** That's the actual wedge, and it's the one thing the mockups execute well (the "measured on your kind of data" eval scorecard, the per-invoice charge tied to a disputable run ID). The recommendation is to lean harder into the governance/audit/dispute layer as the differentiator in messaging and roadmap sequencing, since it's the one thing a single-vendor incumbent (Tally, ClearTax) is structurally unlikely to offer as neutral, auditable infrastructure — and to treat "beat Tally's native AI on accuracy alone" as a losing race.

### 1.3 Market size is real; pricing needs a target-segment correction

~16.5–16.8M active GST-registered taxpayers, ~100,138 registered CA firms (~183,642 professionals, 72% single-partner). SME software/cloud spend is rising (97% of MSMEs allocate up to 20% of revenue to IT/cloud) and the India SaaS market is projected to grow from $9.14B (2025) to $102B by 2035. This is not a fake TAM.

But: typical Indian SME software willingness-to-pay is **₹1,500–5,000/month**. The spec's own GSTR-2B pricing (₹18/invoice, ~₹16–21k/month for a company reconciling 1,000+ invoices) sits well above that — it's priced for a CA firm serving many clients or a larger SMB, not the median small trader. This actually **confirms** the spec's "target Indian SMBs, CA/compliance firms" language, but it means the go-to-market should explicitly lead with CA firms as the primary buyer/reseller for anything priced above ~₹10k/month, with direct-SMB pricing needing its own lower tier or bundled-into-CA-service model. Worth making that split explicit in the pricing table rather than leaving "SMBs and CA firms" as one undifferentiated buyer.

### 1.4 Regulatory risk — two items need resolution before, not during, build

**Wallets (§2, "non-refundable service credits, not stored value"):** RBI's 2026 draft Master Direction on PPIs defines prepaid instruments functionally ("load money, spend later") with no located exemption for closed-loop, non-refundable billing credits. Non-bank issuers face ₹5cr net worth at application, ₹15cr by year 3, and mandatory escrow. The spec treats the credits/not-stored-value distinction as a settled way to dodge licensing — the research found no confirmation that RBI accepts that framing. **This is the single highest-value thing to get a lawyer's opinion on before the ledger schema is designed**, because a wallet that's built as pure prepaid billing and later found to need PPI licensing is expensive to unwind (escrow requirements, audit, possibly a banking partner).

**DPDP Act (§3, §4):** Rules notified Nov 13, 2025, with a three-phase clock: Nov 13, 2025 (Board constituted), **Nov 13, 2026** (Board gets enforcement/penalty power, Consent Manager registration opens), **May 13, 2027** (full consent/rights/retention/breach obligations bind every Data Fiduciary). Penalty exposure starts in about two months from today. Treat DPDP compliance as a dated workstream with a real deadline, not a background architectural property.

### 1.5 GSP/ASP access — budget it as a partnership, not a filing

First-batch GSP requirements were ₹5cr+ paid-up capital and ₹10cr+ average turnover over three years; later batches relaxed this somewhat but a pre-revenue startup almost certainly won't qualify for direct GSTN access at launch. The realistic path is an ASP-layer partnership with an existing GSP (ClearTax, Cygnet, Taxilla, etc.), which means a **revenue share or fixed fee to a GSP partner belongs in the unit economics for agents #1 (partially), #4, and #5** — not just "start the paperwork" as §7 currently states. This changes the margin math on the GST-adjacent agents and should be modeled explicitly before committing to the ₹3/invoice and ₹5/vendor-month price points.

### 1.6 WhatsApp economics — urgent, not hypothetical

Meta moved WhatsApp Business Platform to per-message pricing on Jul 1, 2025 (₹0.86 marketing, ₹0.11 utility/authentication in India, plus BSP markup of $0.003–0.010/message). Service-window messages are free only **until Oct 1, 2026** — after that, Meta charges per-message for service/utility messages inside the 24-hour window too (first 1,000/business number/month stay free). The collections agent's ₹5/invoice-cycle price point should be re-modeled against post-Oct-2026 costs now, since that date is roughly two weeks from today and lands at or before any realistic launch.

---

## 2. Technical requirements

### 2.1 Protocol bet — validated, and ahead of schedule

MCP is no longer a hedge: donated to the vendor-neutral Agentic AI Foundation (Linux Foundation) in Dec 2025, with OpenAI and Block as co-founders and AWS/Google/Microsoft/Cloudflare/GitHub/Bloomberg as members; 10,000+ active servers, 97M monthly SDK downloads, 28% of Fortune 500 running it in production by March 2026. A2A (Google-originated) shipped v1.0 in April 2026 with signed Agent Cards and 150+ supporting organizations, and — notably — **moved into the same Agentic AI Foundation as MCP on Aug 17, 2026**. The two standards are now co-governed, not competing: MCP for tool access, A2A for agent-to-agent discovery, exactly as §3 describes. No change needed here; if anything, the "A2A-style agent card" language in §3 can be upgraded to just "A2A agent card" since it's now the standard, not an analogy.

### 2.2 The trust pipeline is addressing a live, named attack pattern — don't cut it

Prompt injection is the dominant agentic-AI production failure mode as of 2026 (OWASP GenAI's State of Agentic AI Security report), with real CVEs against deployed systems in the last 18 months: Cursor (CVSS 9.8, poisoned execution environment turned allowlisted commands into arbitrary payload delivery), GitHub Copilot (CVSS 9.6, injected prompt via repo comments flipped the agent into unsupervised mode), Microsoft Copilot (CVSS 9.3), MCP infrastructure itself (CVSS 9.6, RCE), and OpenAI Codex CLI (agent output redefined its own sandbox boundary). Every one of these is a "declared scope vs. actual behavior" gap — precisely what §6's automated gate (manifest lint, sandbox scope-boundary check, red-team suite) and the mockup's review pipeline ("Red-team & prompt-injection probes," "Confirmed the agent's actual tool calls never exceed the declared scopes") are built to catch. This is not over-engineering for a v1 with five first-party agents; it's the eval suite you'll need the moment a third party's code runs, and building the pipeline muscle now (even against your own agents) is cheap compared to retrofitting it after an incident.

### 2.3 Sandboxing deferral is reasonable, with one exception

MicroVM-per-run (Firecracker) is confirmed as accepted 2026 practice for isolating untrusted agent code, which validates §4's plan to defer it until third-party code exists — building a modular monolith first is the right sequencing. The one caveat from current practice: **egress allowlisting is treated as equally load-bearing as the VM boundary itself**, because exfiltration doesn't require filesystem access, only a socket and something worth sending. The manifest-declared network allowlist (§3, §5) should be enforced at the network layer from day one, in the monolith, not deferred alongside microVM isolation — the mockup's connection flow already shows this correctly ("Anything not on this list is blocked at the network layer during runs"), so this is a build-order note, not a design gap.

### 2.4 Account Aggregator coverage has a real gap the bank-reconciliation agent needs to design around

AA-enabled accounts cover only ~38% of borrowers nationally (Dec 2025), with metro coverage at 45–50% and Tier 2/3 at 20–30% — meaning roughly 62% of cases still need manual/PDF bank-statement ingestion. For agent #1 (Bank + UPI reconciliation), the statement-upload/OCR path needs to be a first-class flow from v1, not a fallback bolted on later, especially if any early customers are outside metro India.

### 2.5 Mockup-specific observations

The three mockups execute the governance thesis better than most B2B fintech products at this stage:

- The **permission statement** (agent detail page, wizard step 2, connection flow step 3) is the same document rendered three ways — developer declaration, buyer-facing legal statement, and runtime grant — which is exactly the "Android permissions model" §3 calls for, and having one artifact drive all three surfaces is worth preserving as a hard architectural constraint (one manifest schema, three renderers), not just a design coincidence.
- The **run console's approval gate** (a specific invoice crossing a specific rupee threshold, with the source records attached) is a concrete, defensible version of §5's "approval gates on anything that writes/pays/messages" — this is the kind of UI that will actually get a finance team to trust an agent with a login.
- The **developer review pipeline** screen surfaces a prior rejection reason ("red-team found a prompt-injection path... Fixed in this version") as a persistent, visible changelog entry. That's a good instinct: it turns the red-team gate from an opaque black box into part of the developer's own quality record, which will matter for the verified-partner track in §6.
- One inconsistency to fix before these become build references: the buyer-console mockup's "Approvals" nav item routes to the catalog screen, not a dedicated approvals queue — §5 lists "run console: approval gates" as a core buyer surface, so the nav should point somewhere real once an approvals queue exists.

---

## 3. What to change in the spec

Concrete edits worth making to `agent-store-spec.md`, in the same section order:

- **§2, Wallets** — add: "Legal opinion required on whether non-refundable service credits are exempt from RBI's PPI definition, given the 2026 draft Master Direction has no explicit carve-out. Do not finalize ledger schema until this is resolved."
- **§7, item 4 (GST reconciliation)** — change "gated on GSP/ASP onboarding. Start that paperwork immediately" to "gated on a GSP/ASP partnership (direct GSTN access requires capital thresholds a pre-revenue company won't meet). Line up a GSP partner and model their revenue share into the ₹3/invoice price before committing to it."
- **§7, item 1 (Bank/UPI reconciliation)** — revise "no external licensing gate... build first" to note that AI-native competitors (Flick AI, GSTHero/Covoro, and Tally's own native AI) are already live; the differentiator to build toward is outcome pricing + audit trail, not first-mover accuracy.
- **§7, item 2 (WhatsApp collections)** — flag the Oct 1, 2026 WhatsApp per-message pricing change and re-model ₹5/invoice-cycle against post-change costs before launch.
- **§3, Runtime** — add the egress-allowlist-is-day-one note under "Egress limited to exactly what the manifest declares," clarifying it's enforced from the monolith stage, not deferred with microVM isolation.
- **§9, Open items** — add: "RBI PPI exemption status for the wallet model (legal opinion needed)," "GSP/ASP partner selection and revenue-share terms," and "DPDP compliance workstream against the Nov 2026 / May 2027 enforcement dates."

---

## 4. Open questions for you

- Do you want to commission the RBI PPI legal opinion now (before any ledger work starts), or proceed with the wallet design on the current assumption and revisit if challenged?
- Given Tally is now shipping native AI reconciliation, does the connector strategy still anchor on Tally specifically, or is it worth widening agent #1's first integration target (e.g., Zoho Books, which has a cleaner API surface) to reduce platform risk?
- Should CA firms be modeled as the primary buyer/reseller (with a separate, lower-priced direct-SMB tier) rather than one undifferentiated "SMBs and CA firms" buyer segment, given the pricing-vs-willingness-to-pay gap found above?
