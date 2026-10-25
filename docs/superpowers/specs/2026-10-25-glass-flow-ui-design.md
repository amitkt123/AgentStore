# Glass and flow UI

Status: draft for review. Frontend only, except where a section says it uses a UX-2 backend field.

## Intent

Karya's clearest idea is the flow: before a buyer adds an agent, they see what it will read, what it will draft, where it stops to ask, and what it charges. Today the flow is drawn as a row of separate cards. It has no arrows or branches, its order is wrong, and it does nothing while a run is live. The store is a plain list of names. This design makes the UI minimalist but detailed:

- A **glass visual system**: translucent layered surfaces over a calm light field, with depth from blur and shadow, not from colour or borders.
- A **flow canvas**: real nodes joined by arrows, with a fork at every approval. It is the same component everywhere a flow appears: store detail, connect, live run, runs list, Home, and later the Studio.
- A **store that looks like a marketplace**: search, category rows you can scroll sideways (carousels), and cards that show each agent's flow in miniature.

Success: a buyer who has never used Karya can open an agent and answer "what will this touch, when will it ask me, what can it cost" from the picture alone. While a run is live, they can see where it is without reading the timeline.

## Relation to the UX roadmap

`docs/superpowers/specs/2026-10-22-ux-roadmap.md` stays the parent. This design is a new frontend track, **UX-G**, beside UX-2 to UX-5. It changes two roadmap lines on purpose:

- "No decorative gradients": the background gets one soft, **static** light field so the glass has something to refract. It does not animate.
- "Dark mode: not doing": it moves to the last, optional phase (G5), because glass tokens are cheap to theme once they exist.

These roadmap principles still apply and constrain this design:
- Motion shows state changes only. The only moving thing in the flow is the live run's active arrow, and that is a state.
- Never promise a control the platform does not enforce. Flow nodes for declared items keep the "Declared by the developer" label.
- Hide unfinished features instead of disabling them.

## Goals

1. Glass surfaces, elevation and motion settings as tokens, applied to the shell, cards, panels, drawers and the flow.
2. `FlowCanvas`: a layout function that turns blocks into a graph, plus a renderer that draws nodes and SVG arrows. It has a full size and a mini size.
3. The flow is drawn in the order the agent really works, with an approval fork.
4. Live run: the canvas shows state as it changes, and selecting a node filters the timeline to that node's steps.
5. Store browsing: in-page search, category chips, a carousel per category, and rich cards with a mini flow.
6. Connect: the canvas highlights the nodes this setup grants (files plugged into sockets, write scopes) next to the form.
7. Runs list and Home show mini flows. Home's "Your agents" becomes a carousel of glass cards.

## Non-goals

- No new npm dependencies. No React Flow, no animation library, no carousel library. SVG, CSS and a little React are enough, and this follows the no-paid-third-parties rule.
- No backend changes in this track. Where a richer field helps (store card price, publisher), the design reads it if UX-2 has shipped and leaves it out if not.
- No free-form graph editor. Nodes are not draggable, and buyers cannot build flows.
- The Studio flow canvas (G16) is not built here. It reuses `FlowCanvas` when P3c starts.
- No global search or ⌘K (G1). The store search filters only the store page.

## Anti-goals (failure even if it works)

- Glass that hurts reading: text on any translucent surface must meet WCAG AA contrast (4.5:1 for body text).
- Motion for decoration: shimmer, floating blobs, a spinning "thinking" effect.
- A flow picture that implies an order or guarantee the data does not support. The layout rules below are a display convention, and the canvas must not claim more than that.
- Jank: the live run page must stay smooth on a mid-range phone, so no stacked or nested blur layers.

## Constraints from the code today

- Next 14, React 18, CSS modules, tokens in `frontend/src/ui/tokens.css`. No component library.
- A flow is a flat list of block ids: `read:<resource>`, `draft:<resource>`, `write:<resource>`, `gate:<trigger>`, `llm`, `charge`, plus undeclared blocks flagged `UNDECLARED`. They come from `blocksFromManifest` (store, connect) and from `FlowView.blocks` (live run). The backend's `RunFlowService.declaredBlocks` emits scopes, then gates, then llm, then charge. Neither side has edge data.
- The manifest does not link a gate to the scope it guards (gate `post_entries` has no reference to scope `ledger`).
- `CatalogListing` carries no manifest. A card mini flow needs the version's manifest.
- The run list item carries `reachedBlock` but not the full block list.
- The live run polls every 3 s (`useRunPolling`). That stays.

## Design

### 1. Glass tokens and surfaces

Add these tokens to `ui/tokens.css`. Existing colour tokens keep their names.

- Surfaces: `--glass-1` (panels, rgba white ≈ 0.72), `--glass-2` (raised cards and the selected node, ≈ 0.82), `--glass-3` (drawers, menus, top bar, ≈ 0.9). Opacity never goes below 0.72, so contrast holds on any background.
- `--glass-blur: 16px` (desktop) and `10px` (under 768 px). `--glass-edge`: a 1px inner highlight (`inset 0 1px 0 rgba(255,255,255,.7)`) plus a hairline border `rgba(13,26,48,.06)`.
- Elevation: `--shadow-1`, `--shadow-2` and `--shadow-3`, each a soft two-layer shadow tinted toward `--ink`.
- Motion: `--ease` (a standard ease-out curve), `--dur-1: 120ms` (hover, press), `--dur-2: 240ms` (panel and selection changes), `--dur-flow: 1.6s` (one pulse along the active arrow).
- Background: `body` gets one fixed, static radial light field (pale blue to off-white) under `--g`. Auth and empty states keep the dot pattern, as UX-1 decided.

Shared primitive: `ui/Glass.tsx` with a `level` prop (1 to 3), using a `Glass.module.css` class per level. Existing cards (store, Home list, LiveRun panels, Drawer, Topbar, Rail) switch to it. Rule: **never put a blurred surface inside a blurred surface.** Children of a glass panel use flat translucent fills without `backdrop-filter`.

Fallback: when `@supports not (backdrop-filter: blur(1px))` matches, surfaces become solid `--s`.

Reduced motion: under `prefers-reduced-motion: reduce`, durations become 0 and the arrow pulse becomes a static highlighted arrow.

### 2. Flow layout: blocks to a graph

A pure function `layoutFlow(blocks: BlockView[]): FlowGraph` in `features/flow/layout.ts` returns columns of nodes and a list of edges. It is a display convention, and the canvas says so in a quiet caption: "The order is how Karya groups what this agent declared."

Stage order, left to right. Empty stages are dropped.
1. **Reads**: every `read:*` block, stacked in one column.
2. **Thinks**: `llm`, if present.
3. **Drafts**: every `draft:*` block (writes that need approval).
4. **Asks you**: every `gate:*` block. With several gates, they stack in one column.
5. **Writes**: every `write:*` block, plus, after an approval, a "Posts <resource>" node for each draft (see below).
6. **Charges**: `charge`.

Edges:
- Each node connects to every node in the next non-empty column. Reads fan in to the next stage, and the next stage fans out.
- **The approval fork:** each gate has two outgoing edges. "Approved" goes to the next stage. "Rejected" goes to a small end node, "Stops here, nothing is posted". The reject edge is dashed and drawn below the main line.
- **Draft to write:** the backend has one block per draft scope (`draft:ledger`), not a separate post block. The layout adds a derived node "Posts ledger" in the Writes column after the gate, linked to its draft, so the picture shows that the write happens only after approval. The derived node takes its state from the gate: IDLE until the gate is approved, then DONE when the run reaches charge or ends successfully; SKIPPED when the gate is rejected or expires. It is marked `derived: true` and has no metric.
- **Undeclared blocks** (`flag === "UNDECLARED"`) sit off the main line, below the column of their kind, with a red dashed edge from the previous column and the existing "Not declared by the agent" label. They never join the main path.

Layout output: `{ columns: { stage, nodes: NodeView[] }[], edges: { from, to, kind: "main" | "approved" | "rejected" | "undeclared" }[], ends: NodeView[] }`. It is pure and unit-tested against the bank-reconciliation manifest, the demo agent, `fixtures/flow.json`, a manifest with no gate, and one with no LLM.

The fixture `flow.json` contains `read:bank_statement`, `gate:post_entries`, `charge` and an undeclared `write:secret`. The expected layout is Reads → Asks you (fork) → Charges, with `write:secret` hanging below.

### 3. FlowCanvas renderer

`features/flow/FlowCanvas.tsx` replaces `FlowBlocks` at every call site. `FlowBlocks` and its CSS are deleted once nothing uses them.

- **Structure:** a CSS grid of columns. Nodes are the existing button content (kind, title, body, metric, state) on `Glass level 2`. The arrows are one absolutely positioned `<svg aria-hidden>` behind the grid. A `ResizeObserver` measures node rectangles and redraws edges as cubic Bézier curves from the right edge of one node to the left edge of the next, with an arrowhead marker. Edge labels "Approved" and "Rejected" sit at the curve midpoint.
- **Accessibility:** the DOM stays an ordered list in stage order, so screen readers read it top to bottom. Each node's accessible name includes its state and, for a gate, "then Approved continues, Rejected stops". The SVG is decorative.
- **States**, using the existing colours: DONE has a green node edge and a solid green arrow out. WAITING has an amber edge and a soft amber glow (static). FAILED is red. IDLE and SKIPPED use muted ink with grey dashed arrows. The edge into the node the run is on (`reachedBlock`, or the WAITING node) is the **active edge**. On a non-terminal run, one bright dot travels along it every `--dur-flow`. This is the only looping animation in the product.
- **Selection:** clicking a node selects it (`aria-pressed`) and raises it to `Glass level 3`. The parent decides what selection does.
- **Sizes:** `size="full"` (the default) and `size="mini"`. Mini draws 10px dots per node and 1px arrows with no text, at a fixed height of 28px. It is used on cards and run rows and gets `role="img"` with a text label such as "Reads 1, asks you once, then charges".
- **Narrow screens (<768 px):** the full canvas turns vertical. Columns become rows and arrows run downward. The canvas never scrolls sideways.

### 4. Screens

**Store** (`/store`)
- Header: title, the existing one-line promise, and a search box that filters by agent name and category on the client. Category chips (All plus each category in the catalog) sit under it.
- With "All" selected and no search text, the page shows one horizontal **carousel per category**: a scroll-snap row of glass cards with previous and next buttons on desktop and swipe on touch. With a chip or search active, it shows a filtered grid instead.
- Card: name, category, version, "Added" pill, and a mini flow. If UX-2 fields exist, it also shows a one-line job, price per unit, "up to" a maximum per run, and publisher. Without them those lines are absent, not "coming soon".
- Data: the store page fetches each listing's version detail on the server in parallel to build the mini flow. That is N requests, which is acceptable at today's catalog size. When UX-2 lands, the card switches to listing fields, and this design asks UX-2 to add the block id list to `CatalogListingResponse` so the fetches go away.

**Store detail** (`/store/{agent}/{version}`)
- Full `FlowCanvas` across the top on `Glass level 1`, with the caption about ordering.
- Below it, two glass panels as today: the selected node's detail, and "What you agree to". Selecting a gate shows its timeout and preview columns. Selecting a draft shows "You see every change before it is posted".

**Connect** (`/connect/{agent}/{version}`)
- Two columns on desktop, stacked on mobile: the form on the left, a sticky full canvas on the right.
- As the buyer picks a file for a socket, the matching Reads node changes from muted to "plugged in" (a blue edge, plus the file name as its metric). Write nodes and their gate always carry a "This grants" marker, which answers the roadmap finding that connect hid the write scope.

**Live run** (`/runs/{id}`)
- Full canvas at the top with live states and the active edge pulse. A pending gate's GateCard stays directly under the canvas, as now.
- Clicking a node filters the timeline to the steps for that node, with a "Show all steps" reset. `RunStep` has no block id (`kind`, `source`, `detail`, `createdAt` only), so steps are matched by kind. READ/DATA_READ go to Reads, WRITE/WRITE_STAGED/DATA_WRITE go to Drafts and Writes, GATE_* go to Asks you, LLM_CALL goes to Thinks, and COST/USAGE/COST_CAP_REACHED go to Charges. When a stage holds more than one node, the filter covers the whole stage, unless the step's `detail` parses as JSON with a `resource` field matching the node; the builder checks what `detail` carries per kind. Steps of other kinds (COMPLETE, AGENT_LOG, NETWORK_BLOCKED, …) show only under "all".
- When the run ends, the pulse stops and the final arrow takes the terminal colour. The terminal reason sentence sits on the end node the run stopped at.
- The facts aside (spend, blocked attempts, sandbox closes) becomes a glass panel. Spend gets a thin bar showing charged against the cap.

**Runs list** (`/runs`)
- Each row gains a **stage track**: six fixed dots (Reads, Thinks, Drafts, Asks you, Writes, Charges), filled up to the stage of `reachedBlock` and coloured by run status, next to the existing "Reached" text. `RunListItem` has no agent or version id, so a true per-agent mini flow is not possible here without a backend field. The stage track needs only `reachedBlock`. The today-timeline bars become glass and keep their layout.

**Home** (`/home`)
- "Needs you" rows become glass cards with the agent, the money held and an "Open" action, each with the stage track stopped at Asks you.
- "Your agents" becomes a carousel of glass cards: name, "up to" amount per run, the granted scopes as small chips (`ConnectionSummary.grantedScopes`), and Run now. A mini flow joins the card only once UX-2 adds the connection's `agentVersionId`. Today a connection may run an older version than the store shows, and drawing the latest version's flow would misstate it.
- UX-3's fuller Home (running now, spend, first-run checklist) builds on these cards later.

**Shell**
- The rail and top bar move to `Glass level 3` over the light field. The active rail item gets a filled glass pill with `--shadow-1`.
- Drawers and the account menu use level 3 with `--shadow-3`.

### 5. Phases

Each phase is one PR. Every phase updates `docs/status.md` (a new "UX-G glass and flow" row in the Frontend table, with its phase) and `docs/frontend-gaps.md` where something is omitted.

- **G1 Glass foundation:** tokens, `Glass` primitive, background, shell, existing cards and panels moved to glass, reduced-motion and no-blur fallbacks. No layout changes.
- **G2 Flow canvas:** `layoutFlow` and `FlowCanvas` (full and mini), replacing `FlowBlocks` on store detail and live run, with the correct stage order, the approval fork, derived write nodes, the live pulse and node-to-timeline filtering.
- **G3 Connect and runs:** canvas on connect with plugged-in and grants states; stage tracks on runs rows and Home "Needs you".
- **G4 Store browsing:** search, chips, category carousels, rich cards with mini flow, Home "Your agents" carousel.
- **G5 Dark mode (optional):** dark values for every token, including glass levels, following the system setting with a manual override in the account menu. Skip it if no one wants it after G4.

### 6. Testing

- Unit (Vitest): `layoutFlow` cases from §2; node accessible names; mini flow text labels; store search and chip filtering; carousel previous and next buttons moving focus and scroll.
- Component: `FlowCanvas` renders one list item per node, in stage order, with the right states. Clicking a live-run node filters the timeline. Reduced motion removes the pulse class.
- Existing tests that query `FlowBlocks` markup move to the new component. Their meaning stays the same.
- E2E (Playwright): the existing store → connect → run → approve journey still passes, plus one check that the store detail canvas shows "Approved" and "Rejected" for the bank-reconciliation agent.
- Visual: run the app and compare screenshots of store, detail, connect, live run (waiting, done, rejected) and Home, at 1280 px and 390 px.
- Performance: on the live run page, Chrome's performance panel shows no long frames from the pulse or blur on a throttled CPU (4×).

## Left to the builder

- Exact colour and opacity values, the radial light field's colours, and shadow values, within the contrast rule.
- The Bézier curve shape and arrowhead design.
- Whether carousel scrolling uses `scrollBy` or `scrollIntoView` on the next card.

## Calls made in this design (not yet confirmed by the user)

- It reorders the flow on the frontend (Reads → Thinks → Drafts → Asks you → Writes → Charges) instead of changing the backend's block order, and adds a derived "Posts <resource>" node after each gate.
- The background light field is static; only the live run's active arrow moves.
- Dark mode is an optional last phase, not part of the core track.
- Store mini flows fetch version details on the server until UX-2 adds block ids to the listing.
- No new dependencies, including for the canvas and carousels.
