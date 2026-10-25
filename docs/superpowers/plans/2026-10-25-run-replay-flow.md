# Run Replay Flow Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Draw a run's recorded steps as a lane flow diagram with branches, which builds itself live and can be replayed slowly, replacing the step cards and declared canvas on the run page.

**Architecture:** The backend adds `startedAt` to runtime-call steps and returns `id`, `startedAt`, `block` per step. The frontend turns the step list plus gates into a pure graph (`buildReplay`), places it (`placeReplay`), and renders it in `ReplayCanvas`. A timer hook (`useReplay`) decides which beats are visible and which one is mid-animation. `LiveRun` swaps its canvas and timeline for the replay and shows a clicked step's detail in the facts panel.

**Tech Stack:** Spring Boot + JPA + Flyway (Java 21), Next 14 / React 18, CSS modules, Vitest + Testing Library, Playwright.

**Spec:** `docs/superpowers/specs/2026-10-25-run-replay-flow-design.md` (look and motion: `docs/superpowers/specs/assets/2026-10-25-run-replay-mockup.html`)

**Branch:** `feat/run-replay`, cut from `docs/run-replay-design` (which stacks on `feat/ux-g2-flow-canvas`, PR #49).

## Global Constraints

- No new dependencies. Drawing is SVG + CSS; motion is CSS transitions driven by state.
- Glass tokens from G1 (`--glass-1/2/3`, `--glass-edge`, `--glass-line`, `--shadow-*`, `--ok`, `--okS`, `--wa`, `--bad`, `--b`, `--ink*`). Panel background: static dot grid, dots scale with the fit, never move.
- Timing: one beat every **1600ms** at 1×; the line takes **700ms** of it; **0.5×** doubles both. Live catch-up: **300ms** per beat while more than **5** beats are queued.
- Fit: scale = min(1, panelWidth / contentWidth), never below **0.8**; below that the panel scrolls horizontally. No pan, no zoom, no camera movement.
- An arrow and its head never exist before its step; the head appears only when the line has arrived. The not-taken branch fades in, never grows.
- Copy: "Not taken", "Stops here", "Nothing is posted", "Would have posted", "Waiting for you", "Approved", "Rejected", "Step n of N", "Replay", "Pause", "Resume", "Step back", "Step forward", "0.5×", "1×".
- Never add Claude, Anthropic or AI attribution to commits, PRs or docs. Update `docs/status.md` (and its "Last updated") in the same PR.

## Review Focus

1. Steps with `createdAt` or `startedAt` null (legacy rows, the e2e seed): still ordered by list order, one step per beat, time shown blank. Pinned in Task 2.
2. A gate asked twice with the same action: two approval blocks, each paired with its own decision in order. Pinned in Task 2.
3. Polling returns the same steps again, or the run turns terminal while beats are queued: no restart, no repeated animation, the queue drains. Pinned in Task 4.
4. Unmounting mid-replay: every timer cleared, no state update afterwards. Pinned in Task 4.
5. A 60-step run: scale clamps at 0.8 and the panel scrolls; on a live run it stays pinned right only if the user was already at the right end. Pinned in Task 5.

---

### Task 1: Backend records when a runtime call started, and returns step id, startedAt, block

**Files:**
- Create: `backend/src/main/resources/db/migration/V48__run_step_started_at.sql` (`alter table run_step add column started_at timestamptz;`)
- Modify: `backend/src/main/java/com/agentstore/platform/execution/RunStep.java` (field `private Instant startedAt;`)
- Modify: `backend/src/main/java/com/agentstore/platform/execution/RunStepResponse.java`
- Modify: `backend/src/main/java/com/agentstore/platform/execution/RunLifecycleService.java` (`recordStep`, `step`, `charge`)
- Modify: `backend/src/main/java/com/agentstore/platform/runtime/RuntimeAuthFilter.java`, `RunStepRecorder.java`
- Modify: `frontend/src/lib/api/types.ts` (`RunStep`)
- Test: `backend/src/test/java/com/agentstore/platform/runtime/RuntimeResourceEndpointsTest.java`, `backend/src/test/java/com/agentstore/platform/execution/RunDetailControllerTest.java`, `backend/src/test/java/com/agentstore/platform/execution/RunLifecycleServiceTest.java`

**Interfaces:**
- Produces (HTTP): `RunStepResponse(UUID id, RunStepKind kind, RunStepSource source, String detail, String block, Instant startedAt, Instant createdAt)`. USAGE and COST step `detail` gains `"amount"` (the charged amount, a number, 2dp).
- Produces (TS): `interface RunStep { id?: string; kind: RunStepKind; source?: "PLATFORM" | "AGENT"; detail: string; block?: string | null; startedAt?: string | null; createdAt: string | null }`
- Produces (Java): `RuntimeAuthFilter.CALL_STARTED_AT = "agentstore.runtime.callStartedAt"` (request attribute, an `Instant`); `RunLifecycleService.recordStep(UUID runId, RunStepKind kind, RunStepSource source, Map<String,Object> detail, Instant startedAt)` (the old 4-arg overload delegates with `null`).

- [ ] **Step 1: Write the failing tests**

```java
// RuntimeResourceEndpointsTest
@Test
void aWriteStepRecordsWhenTheCallStartedAndAgentLogsDoNot() {
    var agent = fx.runningAgent(new BigDecimal("500.00"));
    call(HttpMethod.POST, "/runtime/v1/resources/journal_entries/records", agent.token(),
            Map.of("records", List.of(Map.of("account", "1000"))));
    call(HttpMethod.POST, "/runtime/v1/logs", agent.token(), Map.of("entries", List.of(Map.of("message", "hi"))));
    var steps = stepRepository.findByRunIdOrderByCreatedAtAsc(agent.run().getId());
    var staged = steps.stream().filter(s -> s.getKind() == RunStepKind.WRITE_STAGED).findFirst().orElseThrow();
    assertThat(staged.getStartedAt()).isNotNull().isBeforeOrEqualTo(staged.getCreatedAt());
    var log = steps.stream().filter(s -> s.getKind() == RunStepKind.AGENT_LOG).findFirst().orElseThrow();
    assertThat(log.getStartedAt()).isNull();
}
```
(Use the log endpoint path and body shape that `LogBatch` / `RuntimeLifecycleController` already accept.)

```java
// RunDetailControllerTest, extend ownerCanReadTraceButOtherOrgCannot
assertThat(ownerResponse.getBody().steps().get(0).id()).isNotNull();
assertThat(ownerResponse.getBody().steps().get(0).block()).isEqualTo(/* the block that test's step was saved with */);
```

```java
// RunLifecycleServiceTest
@Test
void aUsageStepCarriesTheAmountCharged() { /* report usage of 2 units at the fixture's unit price; the USAGE step's detail JSON has "amount" equal to the charged value */ }
```

- [ ] **Step 2: Run them to verify they fail**

Run: `cd backend && mvn -q test -Dtest='RuntimeResourceEndpointsTest,RunDetailControllerTest,RunLifecycleServiceTest'`
Expected: compile failure on `getStartedAt()` / `id()` / `block()`.

- [ ] **Step 3: Implement**

- Migration V48; `RunStep.startedAt` with getter/setter (Lombok already on the class).
- `RuntimeAuthFilter.doFilterInternal`: first line sets `request.setAttribute(CALL_STARTED_AT, Instant.now())`.
- `RunStepRecorder.record`: read the attribute through `RequestContextHolder.getRequestAttributes()` (null-safe: no request → `null`) and call the 5-arg `recordStep`.
- `RunLifecycleService.step(...)` gains an `Instant startedAt` parameter (other callers pass `null`); `charge(...)` copies `detail` into a `LinkedHashMap`, puts `"amount", charged` before recording.
- `RunStepResponse.from` maps the new fields; update `SandboxRunViews` if it constructs the record directly.
- `types.ts`: the `RunStep` shape above (`createdAt` becomes `string | null`; fix any type errors that surfaces with `?? ""` at call sites).

- [ ] **Step 4: Run them to verify they pass, then the backend suite**

Run: `cd backend && mvn -q test -Dtest='RuntimeResourceEndpointsTest,RunDetailControllerTest,RunLifecycleServiceTest' && mvn -q test`
Expected: PASS, suite green. Then `cd frontend && npx tsc --noEmit` clean.

- [ ] **Step 5: Commit**

```bash
git add backend/src frontend/src/lib/api/types.ts
git commit -m "feat(backend): record when a runtime call started; steps return id, block and startedAt"
```

---

### Task 2: `buildReplay`: steps and gates to a lane graph with beats and branches

**Files:**
- Create: `frontend/src/features/replay/graph.ts`
- Test: `frontend/src/features/replay/graph.test.ts`

**Interfaces:**
- Consumes: `RunStep`, `GateDetail` (types.ts); `stepText`, `stepNote` (`features/runs/stepText.ts`); `humanize`, `formatMoney`, `formatWhen` (`lib/format`).
- Produces:

```ts
export type Lane = "Agent" | "Your data" | "You" | "Money" | "Blocked" | "Run";
export const LANES: Lane[] = ["Agent", "Your data", "You", "Money", "Blocked", "Run"];
export type Tone = "done" | "waiting" | "bad" | "ghost";
export interface ReplayBlock {
  id: string; lane: Lane; beat: number; time: string | null;
  label: string;            // small caps: "Agent note" | "Model call" | "Read" | "Draft" | "Write" | "Asks you" | "Charge" | "Blocked" | "End" | "Not taken"
  title: string; detail: string | null; metric: string | null; tone: Tone;
  facts: [string, string][]; // full detail for the side panel
}
export interface ReplayEdge { from: string; to: string; label: "Approved" | "Rejected" | null; ghost: boolean }
export interface ReplayGraph { lanes: Lane[]; blocks: ReplayBlock[]; edges: ReplayEdge[]; beats: number }
export function buildReplay(steps: RunStep[], gates: GateDetail[]): ReplayGraph;
```

Rules the tests pin (from the spec):
- Lane table as in the spec; unknown kinds go to Agent with `title = stepText(s)`. `lanes` lists only lanes that have a block, in `LANES` order.
- Title is `stepText(s)`; AGENT_LOG detail is `stepNote(s)`; reads, drafts and writes: detail `humanize(resource)`, metric `"<count> rows"`; LLM_CALL: detail `model`, metric `"<tokens> tokens"`; USAGE/COST: metric `formatMoney(amount)` when present.
- `GATE_REQUESTED` + the next `GATE_DECIDED` with the same gate key (`detail.action ?? block`) merge into one block with `label "Asks you"`, title = the request's `summary` (falling back to `stepText`). Outcome comes from the decision's `decision`, else from the gate in `gates` with the same action (latest by `decidedAt`), else PENDING. PENDING: detail "Waiting for you", tone `waiting`. APPROVED/REJECTED/EXPIRED: detail `"<Approved|Rejected|Expired> at <HH:MM>"`, metric `"waited <n> min"` when both times exist.
- Decision fork (only once decided): the edge from the gate to the next beat's blocks gets `label` = "Approved" or "Rejected" (EXPIRED counts as "Rejected"); one ghost block in the next beat, lane You, `label "Not taken"`, tone `ghost`, with a ghost edge labelled the other way. Ghost text: after APPROVED, title "Stops here", detail "Nothing is posted"; after REJECTED/EXPIRED, title "Would have posted", detail `humanize` of the latest WRITE_STAGED resource before the gate, else "your data". If the gate is the last step, the ghost alone forms the next beat.
- FAILED, TIMEOUT, CANCELLED, COST_CAP_REACHED, SCOPE_VIOLATION, NETWORK_BLOCKED: tone `bad`. Others: `done`.
- Beats by chained overlap: walk steps in list order; a step joins the current beat when its span `[startedAt, createdAt]` overlaps (strictly) any span already in the beat; a step with no `startedAt` never overlaps. Each new beat gets edges from every non-ghost block of the previous beat.
- Block ids are the step `id`, else `step-<index>`; ghost id `ghost:<gate block id>`.

- [ ] **Step 1: Write the failing tests**

```ts
const at = (s: number) => new Date(Date.UTC(2026, 9, 25, 10, 0, s)).toISOString();
const step = (kind: RunStepKind, detail: object, start: number | null, end: number, id?: string): RunStep =>
  ({ id: id ?? `${kind}-${end}`, kind, detail: JSON.stringify(detail), startedAt: start === null ? null : at(start), createdAt: at(end) });

it("puts each step in its lane, one beat per step when nothing overlaps", () => {
  const g = buildReplay([step("AGENT_LOG", { message: "Reading" }, null, 1), step("DATA_READ", { resource: "expenses", count: 5 }, 2, 3), step("USAGE", { amount: 5 }, null, 4), step("COMPLETE", {}, null, 5)], []);
  expect(g.lanes).toEqual(["Agent", "Your data", "Money", "Run"]);
  expect(g.blocks.map((b) => [b.lane, b.beat])).toEqual([["Agent", 0], ["Your data", 1], ["Money", 2], ["Run", 3]]);
  expect(g.blocks[1]).toMatchObject({ title: "Read your data", detail: "Expenses", metric: "5 rows", tone: "done" });
  expect(g.edges.map((e) => `${e.from}>${e.to}`)).toHaveLength(3);
});

it("puts overlapping calls in one beat, forking into it and joining out of it", () => {
  const g = buildReplay([step("AGENT_LOG", {}, null, 1, "n"), step("DATA_READ", { resource: "bank" }, 2, 6, "a"), step("DATA_READ", { resource: "ledger" }, 3, 5, "b"), step("LLM_CALL", { model: "m", tokens: 9 }, 7, 8, "t")], []);
  expect(g.blocks.find((b) => b.id === "b")!.beat).toBe(1);
  expect(g.edges.map((e) => `${e.from}>${e.to}`).sort()).toEqual(["a>t", "b>t", "n>a", "n>b"]);
});

it("merges an approved gate and draws the rejected path as not taken", () => {
  const g = buildReplay([step("WRITE_STAGED", { resource: "ledger", count: 2 }, 1, 2, "d"), step("GATE_REQUESTED", { action: "post", summary: "Post 2 entries?" }, null, 3, "q"), step("GATE_DECIDED", { action: "post", decision: "APPROVED" }, null, 183, "a"), step("DATA_WRITE", { resource: "ledger", count: 2 }, 184, 185, "w")], []);
  const gate = g.blocks.find((b) => b.label === "Asks you")!;
  expect(gate).toMatchObject({ title: "Post 2 entries?", metric: "waited 3 min" });
  expect(g.blocks.filter((b) => b.label === "Asks you")).toHaveLength(1);
  expect(g.edges.find((e) => e.to === "w")!.label).toBe("Approved");
  expect(g.blocks.find((b) => b.tone === "ghost")).toMatchObject({ title: "Stops here", detail: "Nothing is posted", beat: g.blocks.find((b) => b.id === "w")!.beat });
  expect(g.edges.find((e) => e.ghost)).toMatchObject({ from: gate.id, label: "Rejected" });
});

it("after a rejection the not-taken path names what would have been posted", () => { /* stage ledger, request, decide REJECTED, FAILED: ghost title "Would have posted", detail "Ledger"; edge into FAILED labelled "Rejected" */ });
it("takes an expired gate's outcome from the gates list and counts it as rejected", () => { /* GATE_REQUESTED with no decision step, gates [{action:"post", status:"EXPIRED"}]: gate detail starts "Expired", fork edge label "Rejected" */ });
it("shows a pending gate as waiting, with no fork yet", () => { /* request only, gates PENDING: tone "waiting", detail "Waiting for you", no ghost, no labelled edge */ });
it("pairs each request of a re-asked gate with its own decision", () => { /* request, REJECTED, request, APPROVED (same action): two "Asks you" blocks, first Rejected, second Approved */ });
it("orders steps with no times by list order, one per beat, time blank", () => { /* createdAt null on all: beats 0..n-1, time null */ });
it("keeps a step of an unknown kind, in the Agent lane", () => { /* kind "SOMETHING_NEW" as RunStepKind: one Agent block, title "something new" */ });
it("marks failures and blocked attempts as bad, in their lanes", () => { /* SCOPE_VIOLATION → Blocked lane, tone bad; TIMEOUT → Run lane, tone bad */ });
it("states the charge amount", () => { /* USAGE {amount: 12} → metric formatMoney(12) */ });
```

- [ ] **Step 2: Run to verify they fail**

Run: `cd frontend && npx vitest run src/features/replay/graph.test.ts`
Expected: FAIL, cannot resolve `./graph`.

- [ ] **Step 3: Implement `buildReplay` in `graph.ts`**

- [ ] **Step 4: Run to verify they pass**

Run: `cd frontend && npx vitest run src/features/replay/graph.test.ts`
Expected: 11 passed.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/features/replay/graph.ts frontend/src/features/replay/graph.test.ts
git commit -m "feat(frontend): build a run's replay graph from its steps, with parallel and decision branches"
```

---

### Task 3: `placeReplay` and elbow arrows

**Files:**
- Create: `frontend/src/features/replay/place.ts`
- Test: `frontend/src/features/replay/place.test.ts`

**Interfaces:**
- Consumes: `ReplayGraph`, `Lane` (Task 2); `Box` from `features/flow/geometry`.
- Produces:

```ts
export const COL = 240, ROW = 118, BLOCK_W = 180, BLOCK_H = 92, LANE_X = 110, PAD = 14;
export interface Placed { boxes: Map<string, Box>; lanes: { lane: Lane; top: number; height: number }[]; width: number; height: number }
export function placeReplay(g: ReplayGraph): Placed;
export function elbowPath(a: Box, b: Box): { d: string; labelAt: { x: number; y: number; anchor: "middle" | "start" } };
```

- Column = beat: `x = LANE_X + beat * COL`. Each lane is as many rows tall as its busiest beat; a block's row inside its lane is its index among that lane's blocks in the same beat. `y = PAD + globalRow * ROW`. `width = LANE_X + beats * COL`, `height = PAD*2 + rows * ROW`.
- `elbowPath`: same row → `M x1 y1 H x2` with label above the middle; otherwise out of the right edge, a vertical run at the gap's midpoint with corner radius `min(10, |dy|/2, gap/2 - 2)` (quadratic corners), then straight into `b.x - 1`; label beside the vertical run (`start`). This is the mockup's `edgePath`.

- [ ] **Step 1: Write the failing tests**

```ts
it("gives each lane the height of its busiest beat", () => { /* graph with two reads in beat 1: "Your data" lane height 2*ROW; second read y = first + ROW */ });
it("places beats left to right", () => { /* block in beat 3 has x = LANE_X + 3*COL */ });
it("draws a level arrow as one straight line", () => { expect(elbowPath(box(0,0), box(240,0)).d).toBe(`M180 46 H239`); });
it("enters the next block level after turning", () => {
  const d = elbowPath(box(0, 0), box(240, 236)).d;
  expect(d.endsWith(`H239`)).toBe(true);   // last segment horizontal, so the head lines up
  expect(d).toContain("V");
});
```
(`box(x, y)` = `{ x, y, w: BLOCK_W, h: BLOCK_H }`.)

- [ ] **Step 2: Run to verify they fail** — `npx vitest run src/features/replay/place.test.ts` → FAIL, cannot resolve `./place`.
- [ ] **Step 3: Implement `placeReplay`, `elbowPath`**
- [ ] **Step 4: Run to verify they pass** → 4 passed.
- [ ] **Step 5: Commit**

```bash
git add frontend/src/features/replay/place.ts frontend/src/features/replay/place.test.ts
git commit -m "feat(frontend): place replay blocks in lanes and route elbow arrows"
```

---

### Task 4: `useReplay`: which beats show, which one is drawing, live queue

**Files:**
- Create: `frontend/src/features/replay/useReplay.ts`
- Test: `frontend/src/features/replay/useReplay.test.tsx`

**Interfaces:**
- Produces:

```ts
export const BEAT_MS = 1600, DRAW_MS = 700, CATCH_UP_MS = 300, CATCH_UP_AFTER = 5;
export type Speed = 0.5 | 1;
export interface Replay {
  visible: number;                                     // blocks with beat < visible are on screen
  drawing: { beat: number; phase: "line" | "block" } | null; // beat visible-1 while it animates in
  playing: boolean; speed: Speed;
  replay(): void; pause(): void; resume(): void; back(): void; next(): void; setSpeed(s: Speed): void;
}
export function useReplay(beats: number, opts: { reducedMotion: boolean }): Replay;
export function usePrefersReducedMotion(): boolean;   // matchMedia("(prefers-reduced-motion: reduce)"), false when unavailable
```

Behaviour the tests pin:
- Mount: `visible = beats`, `drawing = null`, not playing (a finished run, or a live run's existing steps, draws at once).
- `beats` grows while mounted: the new beats queue and play one by one, at `BEAT_MS/speed` each, or `CATCH_UP_MS` each while more than `CATCH_UP_AFTER` are queued. A rerender with the same `beats` changes nothing.
- Animating a beat: `visible` becomes beat+1 with `drawing = {beat, phase: "line"}`; after `DRAW_MS/speed` the phase is `"block"`; at the end of the beat `drawing = null` (or the next beat starts). With `reducedMotion`, the beat appears with `drawing = null` straight away; beats still advance on the beat clock.
- `replay()`: `visible = 0`, then plays every beat. `pause()`/`resume()` stop and continue at the same beat. `back()`: stop, `visible = max(1, visible - 1)`, no animation. `next()`: stop, animate one beat if any remain.
- All timers cleared on unmount.

- [ ] **Step 1: Write the failing tests** (Testing Library `renderHook`, `vi.useFakeTimers()`)

```ts
it("starts fully drawn and still", () => { const { result } = renderHook(() => useReplay(4, { reducedMotion: false })); expect(result.current).toMatchObject({ visible: 4, drawing: null, playing: false }); });
it("replays beat by beat: line first, then the block", () => {
  const { result } = renderHook(() => useReplay(3, { reducedMotion: false }));
  act(() => result.current.replay()); act(() => vi.advanceTimersByTime(0));
  expect(result.current).toMatchObject({ visible: 1, drawing: { beat: 0, phase: "line" } });
  act(() => vi.advanceTimersByTime(700)); expect(result.current.drawing).toEqual({ beat: 0, phase: "block" });
  act(() => vi.advanceTimersByTime(900)); expect(result.current.visible).toBe(2);
  act(() => vi.advanceTimersByTime(3200)); expect(result.current).toMatchObject({ visible: 3, drawing: null, playing: false });
});
it("goes half as fast at 0.5×", () => { /* setSpeed(0.5), replay: still phase "line" at 1399ms, "block" at 1400ms */ });
it("pauses and resumes on the same beat", () => { /* replay, advance 1600, pause, advance 10000: visible 2; resume: continues to 3 */ });
it("steps back without animating and forward with it", () => { /* back(): visible 3 of 4, drawing null; next(): visible 4, drawing line */ });
it("plays steps that arrive later, one beat at a time", () => { /* rerender beats 2→4: visible 3 then 4 after 1600ms */ });
it("catches up quickly when far behind", () => { /* rerender 1→9: after 300ms visible is 3 */ });
it("does nothing when polling brings the same beats again", () => { /* rerender 4→4: visible 4, drawing null, no timers pending (vi.getTimerCount() === 0) */ });
it("shows whole beats with reduced motion", () => { /* reducedMotion true, replay: visible 1 with drawing null; advance 1600: visible 2 */ });
it("clears its timers on unmount", () => { /* replay, unmount, expect vi.getTimerCount() === 0 */ });
```

- [ ] **Step 2: Run to verify they fail** — `npx vitest run src/features/replay/useReplay.test.tsx` → FAIL, cannot resolve.
- [ ] **Step 3: Implement** — a reducer for `{visible, drawing, playing, speed, target}` plus one effect owning a single `setTimeout` keyed on that state; `target` is how far playback should go (`beats` for live growth and replay).
- [ ] **Step 4: Run to verify they pass** → 10 passed.
- [ ] **Step 5: Commit**

```bash
git add frontend/src/features/replay/useReplay.ts frontend/src/features/replay/useReplay.test.tsx
git commit -m "feat(frontend): replay clock with paced live queue, pause, stepping and reduced motion"
```

---

### Task 5: `ReplayCanvas`

**Files:**
- Create: `frontend/src/features/replay/ReplayCanvas.tsx`, `ReplayCanvas.module.css`
- Test: `frontend/src/features/replay/ReplayCanvas.test.tsx`

**Interfaces:**
- Consumes: Tasks 2–4.
- Produces: `export function ReplayCanvas({ graph, live, selectedId, onSelect }: { graph: ReplayGraph; live: boolean; selectedId?: string; onSelect?: (id: string) => void }): JSX.Element`

Rendering (mockup is the reference):
- `section aria-label="Run replay"`. Inside: the controls pill (top-left, glass), then a scroll box (`overflow-x: auto`) holding the scaled world: lane bands (alternate faint tint, dashed top border, small-caps lane name), the SVG of edges, and blocks as `button`s with `data-block-id`, `aria-pressed`, class by tone; the latest visible non-ghost beat has the focus ring.
- Edge into beat `b` is rendered only when `b < visible`. While `drawing.beat === b && phase === "line"`, its path has `data-growing` and animates `stroke-dashoffset` from its length to 0 over `DRAW_MS/speed` (length from `getTotalLength()`, guarded for jsdom); it has **no** `marker-end`. Otherwise, a drawn edge has `marker-end` (green head, or grey for ghost). Ghost edges and ghost blocks never grow: they fade in (opacity) when their beat is visible. Edge labels show only once the edge has its head.
- Blocks of the drawing beat render only from phase `"block"`, with the fade-and-rise transition.
- The whole SVG and the world are `aria-hidden`. Next to them, an `ol className="sr-only"` with one `li` per visible block in beat order: `"<label>: <title>, <detail>"`, and for the gate `"…, then <Approved|Rejected>. Not taken: <ghost title>, <ghost detail>"`.
- Controls: Replay, Pause/Resume (one button), Step back, Step forward, 0.5×, 1× (`aria-pressed`), a `role="progressbar"` with `aria-valuenow={visible}`, `aria-valuemax={beats}`, `aria-valuetext="Step n of N"`, and the visible text "Step n of N". All but the speed buttons are disabled while `live`.
- Fit: `ResizeObserver` on the scroll box; scale per Global Constraints; the world div gets `transform: scale(k)` and the scroll box an inner spacer of `width*k` × `height*k`; the dot background size is `22*k`px. On a live run, before new content lands, remember whether `scrollLeft + clientWidth >= scrollWidth - 2`; if so, scroll to the end after it lands.
- Reduced motion: `usePrefersReducedMotion()` passed to `useReplay`; CSS also drops transitions under `prefers-reduced-motion: reduce`.

- [ ] **Step 1: Write the failing tests**

```ts
it("draws every step of a finished run at once", () => { /* graph from Task 2 fixtures: all block buttons present, every non-ghost edge has marker-end */ });
it("draws no arrow before its step, and no head while the line is still growing", () => {
  vi.useFakeTimers(); render(<ReplayCanvas graph={g} live={false} />);
  await user.click(screen.getByRole("button", { name: "Replay" })); act(() => vi.advanceTimersByTime(1600));
  const growing = container.querySelector("path[data-growing]")!;
  expect(growing).not.toHaveAttribute("marker-end");
  expect(container.querySelectorAll("path[data-edge]")).toHaveLength(1);
  act(() => vi.advanceTimersByTime(700));
  expect(container.querySelector(`path[data-to="${growing.getAttribute("data-to")}"]`)).toHaveAttribute("marker-end");
});
it("fades the not-taken branch in without growing it", () => { /* ghost edge never has data-growing */ });
it("tells screen readers the steps and the branch in words", () => { /* sr list contains "then Approved. Not taken: Stops here, Nothing is posted" */ });
it("reports progress as Step n of N", () => { /* progressbar aria-valuetext "Step 4 of 4" */ });
it("disables replay controls on a live run", () => { /* Replay, Pause, Step back, Step forward disabled when live */ });
it("selects a step when its block is clicked", () => { /* onSelect called with the block id; aria-pressed true when selectedId matches */ });
it("stops shrinking at 80% and scrolls instead", () => { /* mock ResizeObserver + clientWidth 400 with a 60-beat graph: world transform contains "scale(0.8)" */ });
it("stays at the right end on a live run only if the user was there", () => { /* scrollLeft at end + rerender with more beats → scrollLeft set to scrollWidth; scrollLeft 0 + rerender → stays 0 */ });
```

- [ ] **Step 2: Run to verify they fail** — `npx vitest run src/features/replay/ReplayCanvas.test.tsx` → FAIL, cannot resolve.
- [ ] **Step 3: Implement `ReplayCanvas.tsx` and `ReplayCanvas.module.css`**
- [ ] **Step 4: Run to verify they pass** → 9 passed.
- [ ] **Step 5: Commit**

```bash
git add frontend/src/features/replay/ReplayCanvas.tsx frontend/src/features/replay/ReplayCanvas.module.css frontend/src/features/replay/ReplayCanvas.test.tsx
git commit -m "feat(frontend): replay canvas with lanes, growing arrows and replay controls"
```

---

### Task 6: Run page uses the replay; step detail in the facts panel; e2e and docs

**Files:**
- Modify: `frontend/src/features/runs/LiveRun.tsx`, `LiveRun.module.css`, `LiveRun.test.tsx`
- Delete: `frontend/src/features/runs/stepFilter.ts`, `stepFilter.test.ts` (only LiveRun used them)
- Modify: `frontend/e2e/seed.ts`, `frontend/e2e/p1-buyer.spec.ts`
- Modify: `docs/status.md`, `docs/frontend-gaps.md`

**Interfaces:**
- Consumes: `buildReplay` (Task 2), `ReplayCanvas` (Task 5). `FlowCanvas` stays for the store page only.

Changes:
- `LiveRun`: remove the `FlowCanvas` section, `outcomesOf`, `layoutFlow`/`toBlockViews` use, the Timeline section and the "Show all steps" filter. Add `<Glass as="section" …><ReplayCanvas graph={buildReplay(detail.steps, gates)} live={!terminal} selectedId={sel} onSelect={toggle} /></Glass>` where the canvas was. The `why` paragraph shows whenever the run is terminal. Pending `GateCard`s and decided-gate `details` stay.
- Facts panel: when a block is selected, it shows that block's `title` as a heading, its `facts` as a `dl`, and a "Close" button that clears the selection; otherwise spend, blocked attempts and sandbox close as today.
- `e2e/seed.ts`: give the two seeded steps `created_at` (`now() - interval '4 minutes'`, `now() - interval '3 minutes'`) and the GATE_REQUESTED detail `{"action":"post_entries","summary":"Post 142 journal entries for August?"}`.
- `p1-buyer.spec.ts` run-page tests: on `/runs/R-E2E1` expect `getByRole("region", { name: "Run replay" })` to contain "Read your data" and "Waiting for you"; after Approve, expect the replay's sr list to contain "then Approved". The store-page assertions (Approved / Rejected / Stops here, nothing is posted) stay, since the store canvas is unchanged.
- `docs/status.md`: UX-G row notes "run replay (this PR)", bump "Last updated". `docs/frontend-gaps.md`: parallel columns need `startedAt`, so runs before V48 draw one step per beat.

- [ ] **Step 1: Write the failing tests** (`LiveRun.test.tsx`)

```ts
it("shows the run as a replay diagram instead of step cards", () => { /* region "Run replay" present; no heading "Timeline"; no list named "Flow" */ });
it("shows a clicked step's full detail in the facts panel, and Close returns to spend", () => { /* click the AGENT_LOG block: facts panel shows the untruncated note; Close → "Spend" visible */ });
it("still offers the approval while a gate waits", () => { /* pending gate: Approve button present (canDecide) and the replay's gate block says "Waiting for you" */ });
```
Delete LiveRun tests that assert the canvas filter, "Show all steps" and "No steps for this part yet".

- [ ] **Step 2: Run to verify they fail** — `npx vitest run src/features/runs/LiveRun.test.tsx` → FAIL on the new tests.
- [ ] **Step 3: Implement the LiveRun, seed, e2e and docs changes above**
- [ ] **Step 4: Verify**

Run: `cd frontend && npx vitest run && npx tsc --noEmit && npx next build`
Expected: all green.
Then with the local backend (memory: colima, `docker start agentstore-pg`, `docker compose up -d seaweedfs redis`, `mvn spring-boot:run -Dspring-boot.run.profiles=dev`, `npm run seed:e2e`): `npx playwright test` → all pass. Screenshot `/runs/R-E2E1` at 1280 and 390 px and check by eye: arrows enter blocks level, heads present only on arrived lines, dots static.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/features/runs frontend/e2e docs/status.md docs/frontend-gaps.md
git commit -m "feat(frontend): run page shows the replay flow; clicked step detail in the facts panel"
```
