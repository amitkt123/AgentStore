# UX-G1 and G2: Glass Foundation and Flow Canvas Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give the portal glass surfaces (G1). Then replace the row of flow cards with a canvas of nodes, arrows and an approval fork, live on the run page (G2).

**Architecture:** G1 adds glass tokens and one `Glass` primitive, then moves existing surfaces onto them with CSS-only changes. G2 adds a pure `layoutFlow` that turns the existing `BlockView[]` into stages and edges, a pure `edgePath` for the geometry, and a `FlowCanvas` that renders the nodes as an ordered list with one decorative SVG of arrows behind it. `StoreFlow` and `LiveRun` switch to it, and `FlowBlocks` is deleted.

**Tech Stack:** Next.js 14 app router, React 18, CSS modules, Vitest + Testing Library, Playwright.

**Spec:** `docs/superpowers/specs/2026-10-25-glass-flow-ui-design.md` (§1 to §3, the store detail and live run parts of §4, and phases G1 and G2 in §5).

## Global Constraints

- Frontend only. No backend, contract or migration change.
- No new npm dependencies, including for the canvas.
- Paths below are relative to `frontend/src` unless they start with `docs/` or `frontend/`.
- Tests are co-located `*.test.ts(x)`. Run from `frontend/`: `npx vitest run <path>`. Before each commit, run `npx tsc --noEmit`.
- Glass surface opacity never goes below 0.72. Text on glass meets WCAG AA (4.5:1 for body text).
- Never put a blurred surface inside a blurred surface. Children of glass use flat translucent fills without `backdrop-filter`.
- Motion shows state changes only. The live run's active-edge pulse is the only looping animation.
- Under `prefers-reduced-motion: reduce`, durations become 0 and the pulse becomes a static highlighted edge.
- Where `backdrop-filter` is unsupported, surfaces become solid `--s`.
- Copy is plain English in sentence case, addressed to "you". Exact strings from the spec are quoted in the tasks.
- Ship as two PRs: G1 (Tasks 1 and 2) and G2 (Tasks 3 to 7). Each PR updates `docs/status.md` (a "UX-G glass and flow" row in the Frontend table, plus the "Last updated" line).
- No AI attribution in commits or PRs. Open each PR and stop, because the user merges.

## Review Focus

1. **A manifest with no scopes, gates or LLM** (only `charge`): the canvas shows one node, no edges, no fork and no crash. Pinned in Task 3 (`layoutFlow`) and Task 5 (`FlowCanvas`).
2. **An undeclared block in a stage that comes first** (for example an undeclared `read:x` when there are no declared reads): it sits off the path, and its dashed edge comes from the nearest earlier column if one exists, or no edge is drawn. No crash. Pinned in Task 3.
3. **A live run whose poll changes block states:** the selected node stays selected, and the active edge moves to the new WAITING or reached node. Pinned in Task 6.
4. **Ten reads on a 390 px phone:** the canvas runs vertically and the page never scrolls sideways. Pinned in Task 7 (e2e `responsive.spec.ts` gains the store detail page) and Task 4 (`edgePath` vertical case).
5. **A rejected or expired gate:** the "Posts <resource>" node is SKIPPED, the reject end node is reached, and the pulse stops on a terminal run. Pinned in Task 3 and Task 6.

---

## Phase G1: Glass foundation

### Task 1: Glass tokens, background and the `Glass` primitive

**Files:**
- Modify: `ui/tokens.css`, `app/globals.css`
- Create: `ui/Glass.tsx`, `ui/Glass.module.css`, `ui/Glass.test.tsx`

**Interfaces:**
- Produces tokens: `--glass-1` (rgba white, about 0.72), `--glass-2` (about 0.82), `--glass-3` (about 0.9), `--glass-blur: 16px` (10px under 768 px, via a media query on `:root`), `--glass-edge` (inset 1px highlight `inset 0 1px 0 rgba(255,255,255,.7)`), `--glass-line: rgba(13,26,48,.06)`, `--shadow-1`, `--shadow-2`, `--shadow-3` (two-layer, tinted to `--ink`), `--ease`, `--dur-1: 120ms`, `--dur-2: 240ms`, `--dur-flow: 1.6s`. Under `prefers-reduced-motion: reduce`, `--dur-1`, `--dur-2` and `--dur-flow` become `0s`.
- Produces: `Glass({ level = 1, as = "div", className, children, ...rest }: { level?: 1 | 2 | 3; as?: "div" | "section" | "aside" | "header" | "nav" | "li"; className?: string } & React.HTMLAttributes<HTMLElement>)`. It renders `as` with classes `surface` and `l{level}` and passes `rest` through (aria attributes, id).
- `Glass.module.css`: `.surface` sets the background from `--glass-{n}`, `backdrop-filter: blur(var(--glass-blur)) saturate(1.2)` (also with the `-webkit-` prefix), a 1px `--glass-line` border, `box-shadow: var(--glass-edge), var(--shadow-{n})` and `border-radius: var(--radius)`. `.surface .surface` sets `backdrop-filter: none`. `@supports not (backdrop-filter: blur(1px))` sets `.surface { background: var(--s); }`.
- `globals.css`: `body` keeps `background-color: var(--g)` and adds one fixed, static radial light field (pale blue `--sky` toward `--g`) via `background-image` with `background-attachment: fixed`. Auth and empty states keep their dots.

- [ ] **Step 1: Write the failing test** in `ui/Glass.test.tsx`:
  - `it("renders the chosen element and level")`: `render(<Glass as="section" level={2} aria-label="P">x</Glass>)`. Expect `getByRole("region", { name: "P" })` to exist, and its `className` to contain `surface` and `l2`.
  - `it("defaults to a level 1 div")`: render `<Glass>x</Glass>`. Expect the element's `tagName` to be `"DIV"` and `className` to contain `l1`.
- [ ] **Step 2: Run** `npx vitest run src/ui/Glass.test.tsx`. Expected: FAIL (module not found).
- [ ] **Step 3: Implement** the tokens, `globals.css` background, `Glass.tsx` and `Glass.module.css` as in Interfaces.
- [ ] **Step 4: Run** `npx vitest run src/ui/Glass.test.tsx && npx tsc --noEmit`. Expected: PASS, no type errors.
- [ ] **Step 5: Commit** with the message `feat(frontend): glass tokens, light field and Glass surface`.

### Task 2: Move the shell and page surfaces onto glass

These are CSS-module changes, plus swapping a few wrappers for `Glass` where a component renders a plain card. Markup roles and text don't change, so the existing tests keep passing.

**Files:**
- Modify (shell, level 3): `ui/Rail.module.css`, `ui/Topbar.module.css`, `ui/Drawer.module.css` (`.panel`), `ui/AccountMenu.module.css` (`.panel`).
- Modify (pages, level 1): `features/store/store.module.css` (`.card`), `features/home/HomeView.module.css` (`.list li`, `.added`), `features/flow/StoreFlow.module.css` (`.panel`), `features/runs/LiveRun.module.css` (`.facts`, `.decided`, `.why`, `.steps li`), `features/runs/runs.module.css` (timeline and table container, whatever classes give them a `--s` background today).
- Modify: `docs/status.md`.

**Interfaces:**
- Consumes: the Task 1 tokens. Shell surfaces copy the `.surface` declarations using `--glass-3` and `--shadow-1`, because they are not React-wrapped. Page cards use `--glass-1` and `--shadow-1`. Rows inside a glass panel (`.steps li`) use `--glass-2` with **no** `backdrop-filter`.
- The active rail item (`.on`) becomes a filled glass pill: `background: var(--glass-2); box-shadow: var(--glass-edge), var(--shadow-1); color: var(--b)`.
- Hover and selection transitions use `transition: background var(--dur-1) var(--ease), box-shadow var(--dur-2) var(--ease)`.

- [ ] **Step 1: Make the CSS changes** above. Replace each `background: var(--s); border: 1px solid var(--l)` on these surfaces with the glass equivalent. Keep layout properties unchanged.
- [ ] **Step 2: Run** `npx vitest run && npx tsc --noEmit && npx next build` from `frontend/`. Expected: all tests pass, the build succeeds.
- [ ] **Step 3: Visual check.** Use the `run` skill to start the app against the local stack. Screenshot `/home`, `/store`, a store detail page and a live run at 1280 px and 390 px. Check that body text on every glass surface is readable (AA) and that no element is blurred twice (rail over content, drawer over page). If the stack can't start, say so in the PR body instead of claiming the check.
- [ ] **Step 4: Run** `npx playwright test e2e/responsive.spec.ts --config=e2e/playwright.config.ts` if the stack is up. Expected: PASS, with no horizontal overflow.
- [ ] **Step 5: Update `docs/status.md`.** Add the Frontend row `| UX-G glass and flow | G1 done; G2 next | this PR | spec \`2026-10-25-glass-flow-ui-design.md\`, plan \`2026-10-25-ux-g1-g2-glass-and-flow-canvas.md\` | G2 to G5 |` and bump "Last updated" to the commit date with "(UX-G1 glass foundation done)".
- [ ] **Step 6: Commit** with the message `feat(frontend): shell, cards and panels on glass`. Open the G1 PR against `master` with the `create-pr` skill and stop.

---

## Phase G2: Flow canvas

Start G2 on a branch from G1's branch (or from master once G1 is merged).

### Task 3: `layoutFlow`: blocks to stages, edges and the approval fork

**Files:**
- Create: `features/flow/layout.ts`, `features/flow/layout.test.ts`

**Interfaces:**
- Consumes: `BlockView`, `BlockKind` and `toBlockViews` from `features/flow/blocks.ts` (unchanged), plus `humanize` from `lib/format`.
- Produces:

```ts
export type Stage = "Reads" | "Thinks" | "Drafts" | "Asks you" | "Writes" | "Charges";
export const STAGES: Stage[]; // in that order
export type GateOutcome = "PENDING" | "APPROVED" | "REJECTED" | "EXPIRED" | null;
export interface FlowNode extends BlockView { stage: Stage; derived: boolean }
export interface EndNode { id: string; gateId: string; label: "Stops here, nothing is posted"; reached: boolean }
export interface FlowEdge { from: string; to: string; kind: "main" | "approved" | "rejected" | "undeclared" }
export interface FlowGraph {
  columns: { stage: Stage; nodes: FlowNode[] }[]; // non-empty stages only, in STAGES order
  offPath: FlowNode[];                             // undeclared blocks
  ends: EndNode[];                                 // one per gate
  edges: FlowEdge[];
  activeTo: string | null;                         // id of the node the active edge points at
}
export function layoutFlow(blocks: BlockView[], opts?: { gateOutcome?: GateOutcome; reachedBlock?: string | null; terminal?: boolean }): FlowGraph;
```

Rules (from spec §2):
- Stage comes from `kind`: Reads → Reads, Thinks → Thinks, Drafts → Drafts, "Asks you" → "Asks you", Writes → Writes, Charges → Charges. Within a stage, input order is kept.
- `undeclared` blocks go to `offPath`, never to `columns`.
- For each `draft:<r>` node, append a derived node to Writes: `{ id: "post:<r>", kind: "Writes", stage: "Writes", title: \`Posts ${humanize(r).toLowerCase()}\`, body: "Only after your approval.", metric: null, undeclared: false, derived: true }`. Its state: `APPROVED` → `DONE`; `REJECTED` or `EXPIRED` → `SKIPPED`; otherwise `SKIPPED` if `terminal`, else `IDLE`. With no gate at all, draft nodes get no derived node.
- Edges: every node in column i connects to every node in column i+1. The kind is `"approved"` when column i is "Asks you", otherwise `"main"`. Each gate node also gets an edge `{ from: gate.id, to: "end:<gate.id>", kind: "rejected" }`, and its end node has `id: "end:<gate.id>"`, `gateId: gate.id`, and `reached` true when `gateOutcome` is `REJECTED` or `EXPIRED`.
- Each off-path node gets one edge `{ kind: "undeclared" }` to it, from the **last node** of the nearest column whose stage comes before the node's stage. If no earlier column exists, it gets no edge.
- `activeTo`: if `terminal`, `null`. Otherwise, the id of the first node in columns with state `WAITING`, else `reachedBlock` if it names a node in columns, else `null`.

- [ ] **Step 1: Write the failing tests** in `features/flow/layout.test.ts`. Build views with `toBlockViews(blocksFromManifest(m), m)` from the bank-reconciliation manifest already used in `blocks.test.ts` (read `bank_statement`, draft `ledger`, gate `post_entries`, llm, charge):
  - `it("orders stages Reads, Thinks, Drafts, Asks you, Writes, Charges")`: `columns.map(c => c.stage)` equals `["Reads","Thinks","Drafts","Asks you","Writes","Charges"]`, and the Writes column is `[{ id: "post:ledger", title: "Posts ledger", derived: true, state: "IDLE" }]` (via `toMatchObject`).
  - `it("forks each gate into approved and rejected")`: edges contain `{ from: "gate:post_entries", to: "post:ledger", kind: "approved" }` and `{ from: "gate:post_entries", to: "end:gate:post_entries", kind: "rejected" }`, and `ends[0].label` is `"Stops here, nothing is posted"`.
  - `it("fans in from several reads")`: with two read scopes, edges contain `read:a → llm` and `read:b → llm`, both `"main"`.
  - `it("derives the post state from the gate outcome")`: `APPROVED` → `post:ledger` is `DONE`; `REJECTED` → `SKIPPED` and `ends[0].reached === true`; `EXPIRED` → `SKIPPED`; `null` with `terminal: true` → `SKIPPED`.
  - `it("lays out the run fixture with the undeclared block off the path")`: views from `lib/api/fixtures/flow.json` via `toBlockViews(json.blocks.map(b => ({ id: b.block, state: b.state, metrics: b.metrics, flag: b.flag })), null)`. Columns are `["Reads","Asks you","Charges"]`, `offPath` ids are `["write:secret"]`, and edges contain `{ from: "gate:post_entries", to: "write:secret", kind: "undeclared" }` (the last node of the nearest earlier column, Asks you).
  - `it("draws a single charge node with no edges")` (Review Focus 1): a manifest with `scopes: []`, `approvalGates: []` and `llm: null` gives one column, `Charges`, with `edges` equal to `[]` and `ends` equal to `[]`.
  - `it("gives an undeclared block in the first stage no edge")` (Review Focus 2): views `[undeclared read:x, charge]` give `offPath.length === 1` and no edge to `read:x`.
  - `it("points the active edge at the waiting node, and at nothing once terminal")`: the fixture with `reachedBlock: "gate:post_entries"` gives `activeTo === "gate:post_entries"`. With `terminal: true`, it gives `null`.
- [ ] **Step 2: Run** `npx vitest run src/features/flow/layout.test.ts`. Expected: FAIL (module not found).
- [ ] **Step 3: Implement** `layoutFlow` in `features/flow/layout.ts` following the rules above.
- [ ] **Step 4: Run** `npx vitest run src/features/flow/layout.test.ts && npx tsc --noEmit`. Expected: PASS.
- [ ] **Step 5: Commit** with the message `feat(frontend): lay out flow blocks as stages with an approval fork`.

### Task 4: `edgePath` geometry

**Files:**
- Create: `features/flow/geometry.ts`, `features/flow/geometry.test.ts`

**Interfaces:**
- Produces:

```ts
export interface Box { x: number; y: number; w: number; h: number } // relative to the canvas
export function edgePath(a: Box, b: Box): string;      // SVG path d
export function edgeMidpoint(a: Box, b: Box): { x: number; y: number };
```

- Horizontal when `b.x >= a.x + a.w - 1`: a cubic from `(a.x+a.w, a.y+a.h/2)` to `(b.x, b.y+b.h/2)`, with both control points offset horizontally by `max(24, (b.x - (a.x+a.w)) / 2)`.
- Otherwise vertical (stacked layout, or an off-path or end node below): from `(a.x+a.w/2, a.y+a.h)` to `(b.x+b.w/2, b.y)`, with control points offset vertically by `max(16, (b.y - (a.y+a.h)) / 2)`.
- Paths use `M … C …` with numbers rounded to 1 decimal.

- [ ] **Step 1: Write the failing tests:**
  - `it("curves right between side-by-side boxes")`: `edgePath({x:0,y:0,w:100,h:40},{x:160,y:0,w:100,h:40})` equals `"M 100 20 C 130 20 130 20 160 20"`.
  - `it("curves down when the next box is below")` (Review Focus 4): `edgePath({x:0,y:0,w:100,h:40},{x:0,y:80,w:100,h:40})` equals `"M 50 40 C 50 60 50 60 50 80"`.
  - `it("finds the midpoint")`: for the horizontal pair, it returns `{ x: 130, y: 20 }`.
- [ ] **Step 2: Run** `npx vitest run src/features/flow/geometry.test.ts`. Expected: FAIL.
- [ ] **Step 3: Implement** both functions.
- [ ] **Step 4: Run** the same command. Expected: PASS.
- [ ] **Step 5: Commit** with the message `feat(frontend): flow edge geometry`.

### Task 5: `FlowCanvas` renderer

**Files:**
- Create: `features/flow/FlowCanvas.tsx`, `features/flow/FlowCanvas.module.css`, `features/flow/FlowCanvas.test.tsx`

**Interfaces:**
- Consumes: `FlowGraph`, `FlowNode`, `EndNode` and `STAGES` (Task 3); `edgePath` and `edgeMidpoint` (Task 4); `BlockState` from `lib/api/types`.
- Produces:

```ts
export function FlowCanvas(props: { graph: FlowGraph; selectedId?: string; onSelect?: (id: string) => void; showState?: boolean; live?: boolean; caption?: boolean; endText?: string | null }): JSX.Element;
export function FlowMini({ graph }: { graph: FlowGraph }): JSX.Element;
export function miniLabel(graph: FlowGraph): string;
```

- **Markup:** the root `div` has class `canvas` and `position: relative`. Inside it is an `<ol aria-label="Flow">` with one `<li data-stage>` per column, in order. Each column holds the column's node buttons, stacked. After the columns comes one more `<li>` labelled "Off the declared path", but only when `offPath` is non-empty; it holds those nodes. Gate end nodes render inside the gate's column, below the gate, as a non-button `<div data-end>` showing `label`, or `endText` when `reached && endText`. The `<svg aria-hidden="true">` is absolutely positioned behind the list.
- **Node button:** the same content as today's `FlowBlocks` (kind, title, body, metric, undeclared flag "Not declared by the agent", and state text when `showState`, using the existing `STATE_TEXT` copy: Done, Waiting for you, Failed, Skipped, Not started). Move that map into `FlowCanvas.tsx`. Add `data-node-id`, `aria-pressed={selectedId === id}` and class `${state}`. Gate nodes add `<span className="sr-only">, then Approved continues, Rejected stops</span>`. Derived nodes add `data-derived`.
- **Edges:** for each `graph.edges` item, render `<path data-edge={kind} data-from data-to d={…} marker-end="url(#arrow)">`. `d` comes from `edgePath` using boxes measured by `getBoundingClientRect` relative to the root, keyed by `data-node-id` or `data-end` id. Measure in a `useLayoutEffect` and a `ResizeObserver` on the root (guard `typeof ResizeObserver !== "undefined"`), storing the boxes in state. "approved" and "rejected" edges get `<text>` labels "Approved" and "Rejected" at `edgeMidpoint`. Rejected and undeclared edges are dashed, and undeclared ones are red.
- **Active edge and pulse:** the edge whose `to === graph.activeTo` gets `data-active="true"`. When `live` is also true, render one `<circle className={styles.pulse} style={{ offsetPath: \`path("${d}")\` }} r={3}>` for it. The CSS animates `offset-distance` from 0% to 100% over `var(--dur-flow)`, infinitely. Under `prefers-reduced-motion: reduce`, `.pulse { display: none }`, and the active path gets a stronger stroke instead.
- **States in CSS:** use the existing colour tokens. DONE has a green edge, WAITING an amber edge plus a static amber glow, FAILED red, IDLE and SKIPPED muted with dashed outgoing paths. A node's outgoing path colour follows the node's state (class on the path from the `from` node's state). Nodes use `--glass-2` with `--shadow-1` and **no** `backdrop-filter`. A selected node uses `--glass-3`, `--shadow-2` and a `--b` edge. Transitions use `--dur-2`.
- **Layout CSS:** at ≥768 px, the `ol` is `display: grid; grid-auto-flow: column; grid-auto-columns: minmax(180px, 1fr); gap: 48px`. Under 768 px it is a single column with `gap: 28px`, and `edgePath` switches to vertical by itself. The canvas never sets `overflow-x: auto`. Instead, nodes wrap text (`overflow-wrap: anywhere`).
- **Caption:** when `caption` is set, show `<p>` "The order is how Karya groups what this agent declared." under the canvas.
- **`FlowMini`:** `<div role="img" aria-label={miniLabel(graph)}>`, a row of 10px dots (one per column node, coloured by state) joined by 1px lines, height 28px, no text. `miniLabel` produces, for example, `"Reads 1, thinks, drafts 1, asks you once, writes 1, then charges"`: stage words in order, counts for Reads, Drafts and Writes, "once" or "N times" for Asks you, and "then charges" last. A graph with only Charges gives `"Charges"`.

- [ ] **Step 1: Write the failing tests** in `FlowCanvas.test.tsx`, using `layoutFlow` on the bank-reconciliation views (as in Task 3):
  - `it("lists nodes in stage order")`: `within(getByRole("list", { name: "Flow" })).getAllByRole("button").map(b => b.dataset.nodeId)` equals `["read:bank_statement","llm","draft:ledger","gate:post_entries","post:ledger","charge"]`.
  - `it("states each node's status in words, not only colour")`: port the existing `FlowBlocks.test.tsx` case. All five state texts are present when `showState`.
  - `it("selects a node when clicked")`: clicking the gate button calls `onSelect` with `"gate:post_entries"`, and with `selectedId="gate:post_entries"` that button has `aria-pressed="true"`.
  - `it("tells screen readers about the fork")`: the gate button's accessible name matches `/then Approved continues, Rejected stops/`.
  - `it("draws an arrow per edge, with the fork labelled")`: `container.querySelectorAll("path[data-edge]")` has length `graph.edges.length`. There is one `path[data-edge="rejected"]`, and the SVG text includes "Approved" and "Rejected". The reject end shows "Stops here, nothing is posted".
  - `it("marks undeclared nodes off the path")`: with the fixture graph, the "Off the declared path" item contains "Not declared by the agent".
  - `it("pulses only on a live run")`: fixture graph (with `activeTo` set). With `live`, there is one `circle` and one `path[data-active="true"]`. Without `live`, there is zero `circle` and the active path is still present.
  - `it("renders a lone charge node without arrows")` (Review Focus 1): the graph from Task 3's charge-only case renders one button and zero `path[data-edge]`.
  - `it("labels the mini flow in words")`: `miniLabel(bankGraph)` equals `"Reads 1, thinks, drafts 1, asks you once, writes 1, then charges"`, and `getByRole("img")` for `<FlowMini>` has that name.
- [ ] **Step 2: Run** `npx vitest run src/features/flow/FlowCanvas.test.tsx`. Expected: FAIL.
- [ ] **Step 3: Implement** `FlowCanvas.tsx` and `FlowCanvas.module.css` per Interfaces.
- [ ] **Step 4: Run** `npx vitest run src/features/flow && npx tsc --noEmit`. Expected: PASS.
- [ ] **Step 5: Commit** with the message `feat(frontend): flow canvas with arrows, approval fork and live pulse`.

### Task 6: Use the canvas on store detail and the live run, with a timeline filter

**Files:**
- Create: `features/runs/stepFilter.ts`, `features/runs/stepFilter.test.ts`
- Modify: `features/flow/StoreFlow.tsx`, `features/flow/StoreFlow.module.css`, `features/flow/StoreFlow.test.tsx`, `features/runs/LiveRun.tsx`, `features/runs/LiveRun.module.css`, `features/runs/LiveRun.test.tsx`
- Delete: `features/flow/FlowBlocks.tsx`, `features/flow/FlowBlocks.module.css`, `features/flow/FlowBlocks.test.tsx`

**Interfaces:**
- Consumes: `layoutFlow`, `FlowCanvas` and `FlowNode` (Tasks 3 and 5), plus `Glass` (Task 1).
- Produces: `stepsForNode(steps: RunStep[], node: FlowNode): RunStep[]` in `features/runs/stepFilter.ts`. The kind-to-stage map: `READ`, `DATA_READ` → Reads; `WRITE`, `WRITE_STAGED`, `DATA_WRITE` → Drafts and Writes; `GATE`, `GATE_REQUESTED`, `GATE_DECIDED` → Asks you; `LLM_CALL` → Thinks; `COST`, `USAGE`, `COST_CAP_REACHED` → Charges. Every other kind matches no node. When a step's `detail` parses as a JSON object with a string `resource` field, the step also has to match the node's resource (the part after `:` in the node id; `post:<r>` matches `<r>`). Otherwise every step of the stage's kinds matches.

**StoreFlow:**
- Build `graph = layoutFlow(views)` and render `<Glass as="section" aria-label="Flow"><FlowCanvas graph={graph} selectedId={cur.id} onSelect={setSel} showState={false} caption /></Glass>`.
- Selection uses graph nodes, so derived `post:*` nodes are selectable too. The default selection is the first node.
- In the selected-node panel, a gate shows `"Waits up to {timeoutMinutes} minutes for an answer"`, plus `"You will see: {columns.map(humanize).join(", ")}"` when `preview.columns` exists. A draft or derived post node shows `"You see every change before it is posted"`.

**LiveRun:**
- `gateOutcome` is the status of the most recently decided gate in `gates` (by `decidedAt`), else `"PENDING"` if any is pending, else `null`. Pass `reachedBlock: flow.reachedBlock` and `terminal: isTerminal(detail.status)`.
- Render `<FlowCanvas graph={graph} live={!terminal} selectedId={sel} onSelect={toggle} endText={why} />` in place of `FlowBlocks`. Clicking the selected node again clears the selection.
- The timeline lists `sel ? stepsForNode(detail.steps, node) : detail.steps`. When `sel` is set, show a "Show all steps" button above the list. A node whose filter returns nothing shows "No steps for this part yet."
- Selection is stored by node id in `useState`, so it survives polls (Review Focus 3).
- The facts aside becomes `<Glass as="aside" aria-label="Run facts">`. Spend gets a bar under the text: `<div role="meter" aria-label="Spend" aria-valuemin={0} aria-valuemax={cap} aria-valuenow={charged}>` with a fill width of `min(100, charged/cap*100)%`.

- [ ] **Step 1: Write the failing tests:**
  - `stepFilter.test.ts`: `it("matches steps to a node by kind")`: steps `[DATA_READ, LLM_CALL, GATE_REQUESTED, AGENT_LOG]`. The Reads node gets `[DATA_READ]`, the gate node `[GATE_REQUESTED]`, and `AGENT_LOG` matches nothing. `it("narrows by resource when the detail names one")`: a `DATA_READ` with detail `{"resource":"bank_statement"}` matches `read:bank_statement` but not `read:other`, and one with detail `"plain"` matches both.
  - `LiveRun.test.tsx`: `it("filters the timeline to a clicked node and shows all again")`: with the existing `snap` plus an `LLM_CALL` step, clicking the gate button leaves only "Asked for your approval". Clicking "Show all steps" brings back "Asked the AI model".
  - `LiveRun.test.tsx`: `it("keeps the selection and moves the active edge when a poll changes state")` (Review Focus 3): render with the gate WAITING, click the gate, then re-mock `useRunPolling` with the gate DONE and `reachedBlock: "charge"`, and `rerender`. The gate button still has `aria-pressed="true"`, and `path[data-active="true"]` has `data-to="charge"`.
  - `LiveRun.test.tsx`: `it("stops the pulse and marks the reject end on a rejected run")` (Review Focus 5): the existing terminal snapshot with gate REJECTED (and `blocks` including `draft:ledger` with a manifest, so `post:ledger` exists) gives zero `circle`, a `[data-end]` showing the terminal reason text, and the `post:ledger` button text "Skipped".
  - `LiveRun.test.tsx`: `it("shows spend as a meter")`: `getByRole("meter", { name: "Spend" })` has `aria-valuenow="96"` and `aria-valuemax="500"`.
  - `StoreFlow.test.tsx`: `it("shows the gate's wait and preview when selected")`: clicking the gate shows "Waits up to 30 minutes for an answer". Clicking "Posts ledger" shows "You see every change before it is posted".
  - Update any existing `StoreFlow.test.tsx` and `LiveRun.test.tsx` assertions that relied on `FlowBlocks` button order to use `data-node-id` or names.
- [ ] **Step 2: Run** `npx vitest run src/features/runs src/features/flow`. Expected: the new tests FAIL.
- [ ] **Step 3: Implement** `stepFilter.ts` and the StoreFlow and LiveRun changes, then delete the three `FlowBlocks` files. Check with `grep -rn FlowBlocks src` that the only remaining matches are in deleted files (none).
- [ ] **Step 4: Run** `npx vitest run && npx tsc --noEmit`. Expected: PASS.
- [ ] **Step 5: Commit** with the message `feat(frontend): flow canvas on store detail and live run; timeline follows the selected node`.

### Task 7: E2E, visual check, docs and the G2 PR

**Files:**
- Modify: `frontend/e2e/p1-buyer.spec.ts`, `frontend/e2e/responsive.spec.ts`, `docs/status.md`, `docs/frontend-gaps.md`

- [ ] **Step 1: Extend e2e.** In `p1-buyer.spec.ts`, after the store detail heading check, add `await expect(page.getByText("Approved", { exact: true })).toBeVisible(); await expect(page.getByText("Rejected", { exact: true })).toBeVisible(); await expect(page.getByText("Stops here, nothing is posted")).toBeVisible();`. In `responsive.spec.ts`, add the seeded agent's store detail page and `/runs/R-E2E1` to the pages checked for overflow at each width (Review Focus 4).
- [ ] **Step 2: Run** `npm run seed:e2e && npx playwright test --config=e2e/playwright.config.ts` from `frontend/` with the stack up. Expected: PASS. If the stack can't start, record that in the PR body.
- [ ] **Step 3: Visual and performance check.** With the `run` skill, screenshot store detail and a live run in three states (waiting, done, rejected) at 1280 px and 390 px. On the waiting run, record a Chrome performance trace with CPU throttled 4× for 5 s and confirm there are no long frames from the pulse or blur. Turn on "reduce motion" in OS or devtools emulation and confirm the pulse is gone and the active edge is still highlighted.
- [ ] **Step 4: Docs.** In `docs/status.md`, set the UX-G row to "G1, G2 done; G3 next", add this PR, and bump "Last updated" with "(UX-G2 flow canvas done)". In `docs/frontend-gaps.md`, under "Known visual differences", add: "The flow order is a display convention (spec `2026-10-25-glass-flow-ui-design.md` §2); the backend still emits scopes, then gates." Also update G11's line if the canvas changes how missing live-run descriptions look (titles still show, bodies stay empty).
- [ ] **Step 5: Commit** with the message `test(frontend): e2e for the flow fork; docs for UX-G2`. Open the G2 PR with the `create-pr` skill and stop.
