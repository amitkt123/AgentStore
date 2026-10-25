# Run replay flow: design

Date: 2026-10-25. Track: UX-G, between G2 (flow canvas) and G3. Mockup:
[`assets/2026-10-25-run-replay-mockup.html`](assets/2026-10-25-run-replay-mockup.html) (open it in a browser; it is
the locked look and motion).

## Why

On a run's page the steps are a vertical stack of cards ("Agent note", "Read your data", "Charge recorded"…). The
diagram above them shows what the agent *declared* (Reads → Charges), not what *happened*. The user wants the steps
themselves drawn as a flow diagram: blocks joined by arrows, branching where the run branched, that they can replay
at a pace slow enough to follow. Nothing is re-executed; a replay redraws the record.

It must work for any run that actually happens, built from the steps Karya recorded, not from a fixed template.

## Goals

- Every recorded step of a run is a block on a diagram, with the same text the timeline shows today (time, what
  happened, detail such as "Expenses · 5 rows" or "₹5 of ₹100").
- Arrows show the order the run took, including branches:
  - **Parallel**: steps that were in flight at the same time sit in the same column; arrows fork into them and
    join out of them.
  - **Decision**: at an approval the flow forks into "Approved" and "Rejected". The path taken is drawn normally;
    the path not taken is drawn faded and dashed and its block says "Not taken".
- Replay: the diagram builds itself step by step, slowly, and the user controls it.
- A live run builds the same diagram as steps arrive.

## The locked design (agreed against the mockup)

**Lanes.** One horizontal row per actor, time runs left to right. Only lanes that have a step are shown. Lane names
sit at the left inside the diagram.

| Lane | Step kinds |
|---|---|
| Agent | `AGENT_LOG`, `LLM_CALL` |
| Your data | `DATA_READ`, `READ`, `WRITE_STAGED`, `DATA_WRITE`, `WRITE` |
| You | `GATE_REQUESTED` + its `GATE_DECIDED`, merged into one block; legacy `GATE` |
| Money | `USAGE`, `COST`, `COST_CAP_REACHED` |
| Blocked | `SCOPE_VIOLATION`, `NETWORK_BLOCKED` (my call: their own lane, red, so a blocked attempt is never mistaken for work) |
| Run | `COMPLETE`, `FAILED`, `TIMEOUT`, `CANCELLED` |

A kind added later that is not in the table goes in the Agent lane with its humanised kind as title, so nothing
recorded is ever dropped.

**Blocks.** Glass card, about 180px wide: time (mono), kind label (small caps), title, detail, metric (mono). The
step text comes from the existing `stepText` / `stepNote` so the diagram and any list view never disagree. The
approval block shows its outcome and who and when ("Approved by you at 10:05 · waited 3 min"); while it is still
pending it says "Waiting for you" in the warning colour. A failed or blocked step uses the bad colour. The most
recent block has the blue focus ring.

**Arrows.** Leave a block from its right edge, turn at right angles with a small rounded corner halfway across the
gap, and enter the next block straight and level, so the head always lines up with the line. Same-row arrows are a
straight line. Heads are the same colour as their line (green for done, grey for not taken). Fork arrows carry the
labels "Approved" / "Rejected".

**Motion (the user was explicit on all of these).**
- An arrow does not exist before its step happens. It grows from the source; its **head appears only when the line
  reaches the next block**; the block then fades and rises in.
- One beat (a column) every 1.6s at 1×; the line takes about 0.7s of it. 0.5× doubles both. Parallel steps in one
  beat grow and appear together.
- The not-taken branch fades in (it does not grow), so it never looks like it ran.
- **The view never moves on its own.** No camera follow, no zooming to the active block, while building or replaying.

**View.** A fixed panel with a static dotted background (dots scale with the fit, they do not move). No panning,
no zoom, no infinite canvas; the user rejected all three. The whole run is scaled to fit the panel. My call, shown
to the user without objection: it stops shrinking at 80% scale, and past that the panel gets an ordinary horizontal
scrollbar. Re-fit on resize.

**Controls.** A glass pill over the top-left of the panel: Replay, Pause/Resume, Step back, Step forward, 0.5× / 1×,
a progress bar, "Step n of N". A finished run opens fully drawn; Replay clears it and builds it again. Step back
shows the previous beat at once without animation; Step forward animates one beat.

## Building the graph from real steps

The backend gives a flat, ordered list of steps (`kind`, `source`, `detail` JSON, `createdAt`) and no links between
them. Two of the three things the diagram needs can be derived; one needs a small backend addition.

1. **Sequence**: steps in recorded order. Each beat's blocks get arrows from every block of the previous beat
   (fork and join both fall out of this).
2. **Decision fork**: a `GATE_REQUESTED` and its `GATE_DECIDED` (same `action`) merge into one approval block. Its
   outcome picks the taken branch: the next real steps. The not-taken branch is one ghost block: "Stops here,
   nothing is posted" when approved; "Would have posted <resource>" when rejected or expired, with the resource taken
   from the gate's staged write. A gate still pending has no fork drawn yet.
3. **Parallel**: needs to know which steps overlapped in time. The platform records each step when a call
   *finishes*. **Backend change (my call):** also keep when the platform *received* the call, as `startedAt`, and
   return `id`, `startedAt` and the existing `block` on `RunStepResponse`. Steps with no duration (agent notes,
   platform events) get `startedAt = createdAt`. Two steps share a beat when their `[startedAt, createdAt]` spans
   overlap; a beat is a maximal run of consecutive overlapping steps. Older steps without `startedAt` fall back to
   one step per beat, so old runs still draw, just without parallel columns.

Layout, edges and beats are a pure function of the step list and gates (same style as `layoutFlow`), unit-tested
apart from the rendering.

## Where it goes

My call, to check: on the run page the replay diagram **replaces both** the step cards and the declared Reads →
Charges canvas, because the user's complaint was that the steps were cards, and two diagrams on one page would
compete. What stays: the status line, the approval panel with its Approve / Reject buttons, and the facts panel
(spend, blocked attempts). The declared canvas stays on the store detail page, unchanged.

**Live runs.** The diagram grows as polling brings steps. New steps queue and play at the 1× pace, so a burst of
ten steps does not flash in at once; the queue drains faster (0.3s per beat) when it is more than five beats behind
(my call). Replay controls are disabled until the run ends. If the panel has a scrollbar and the user is already at
its right end, it stays at the right end as steps arrive (like a terminal); otherwise their scroll position is kept.

## Settled calls (mine, from the builder check)

- Opening a run that is still live draws the steps it already has at once; only steps arriving after that go
  through the paced queue.
- A gate asked again with the same `action` pairs each request with the next decision, in order.
- A gate that expired with no `GATE_DECIDED` step takes its outcome from the run's gates data, which the run page
  already loads.
- Beats use chained overlap: a step joins the current beat if its span overlaps any step already in it.
- Clicking a block replaces the old click-a-node timeline filter: it shows that step's full detail (the untruncated
  note, the resource, rows, tokens or amount) in the facts panel, with a "Close" to return to spend and blocked
  attempts.

## Accessibility and reduced motion

- The diagram is `aria-hidden`; next to it is a visually hidden ordered list of the same steps in order, with
  branches stated in words ("Approval: approved. Not taken: stops here, nothing is posted").
- Controls are real buttons with names; progress is a `role="progressbar"` with value text "Step n of N".
- Reduced motion: no growing, fading or rising; each beat appears whole. Replay and stepping still work.
- State is never colour alone: "Not taken", "Waiting for you", "Failed" and "Blocked" are written on the block.

## Non-goals

- Pan, zoom, infinite canvas, minimap.
- Re-running anything; a replay only redraws the record.
- Changing the store detail canvas or the Home and Runs list pages.
- Exporting the replay as video or GIF.

## Anti-goals (a failure even if it works)

- The view moving or zooming by itself.
- Any arrow or arrowhead visible before its step happened.
- Steps flashing past too fast to read.
- A not-taken branch that looks like it ran.
- A block for something that never happened, other than the clearly marked "Not taken" ghost.
- Dropping a recorded step because its kind is unfamiliar.

## Constraints

- Next 14, React 18, CSS modules, glass tokens from G1. No new dependencies; SVG and CSS for drawing and motion.
- Backend: Spring Boot; `startedAt` is a nullable column via a Flyway migration; existing step consumers unaffected.
- Vitest for layout and component tests, Playwright e2e for the run page. `docs/status.md` updated in the same PR.

## Left to the builder

- How `startedAt` is captured per runtime call (a request attribute, a filter, or passing it through the recorder).
- Exact block size, gaps and lane heights, within the mockup's proportions.
- How the replay timer is structured, as long as pause and step are exact and timers are cleared on unmount.
- Whether the hidden step list reuses the existing timeline component.
