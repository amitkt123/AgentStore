import { act, fireEvent, render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { readFileSync } from "node:fs";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ReplayCanvas } from "./ReplayCanvas";
import { buildReplay } from "./graph";
import type { RunStep, RunStepKind } from "@/lib/api/types";

const at = (s: number) => new Date(Date.UTC(2026, 9, 25, 10, 0, s)).toISOString();
const step = (kind: RunStepKind, detail: object, start: number | null, end: number, id: string): RunStep =>
  ({ id, kind, detail: JSON.stringify(detail), startedAt: start === null ? null : at(start), createdAt: at(end) });

// One block per beat: n > r > c > e.
const seq = buildReplay([
  step("AGENT_LOG", { message: "Reading" }, null, 1, "n"), step("DATA_READ", { resource: "expenses", count: 5 }, 2, 3, "r"),
  step("USAGE", { amount: 5 }, null, 4, "c"), step("COMPLETE", {}, null, 5, "e"),
], []);
// Approved gate: d > q > w, with the not-taken ghost beside w.
const gated = buildReplay([
  step("WRITE_STAGED", { resource: "ledger", count: 2 }, 1, 2, "d"), step("GATE_REQUESTED", { action: "post", summary: "Post 2 entries?" }, null, 3, "q"),
  step("GATE_DECIDED", { action: "post", decision: "APPROVED" }, null, 183, "a"), step("DATA_WRITE", { resource: "ledger", count: 2 }, 184, 185, "w"),
], []);
const long = (n: number) => buildReplay(Array.from({ length: n }, (_, i) => step("AGENT_LOG", { message: `m${i}` }, null, i + 1, `s${i}`)), []);

const edges = (c: HTMLElement) => c.querySelectorAll("path[data-edge]");

describe("ReplayCanvas", () => {
  it("keeps the controls in normal flow above the scroll box, never over the canvas", () => {
    render(<ReplayCanvas graph={gated} live={false} />);
    const controls = screen.getByRole("group", { name: "Replay controls" });
    const scroll = document.querySelector("[data-scroll]")!;
    expect(controls.compareDocumentPosition(scroll) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(scroll.contains(controls)).toBe(false);
    const rule = /\.controls\s*\{([^}]*)\}/.exec(readFileSync("src/features/replay/ReplayCanvas.module.css", "utf8"))![1];
    expect(rule).not.toMatch(/position:\s*(absolute|fixed)/);
  });

  afterEach(() => { vi.useRealTimers(); vi.unstubAllGlobals(); });

  it("draws every step of a finished run at once", () => {
    const { container } = render(<ReplayCanvas graph={gated} live={false} />);
    for (const b of gated.blocks) expect(container.querySelector(`[data-block-id="${b.id}"]`)).not.toBeNull();
    const real = [...edges(container)].filter((p) => p.getAttribute("data-edge") !== "ghost");
    expect(real).toHaveLength(gated.edges.filter((e) => !e.ghost).length);
    for (const p of real) expect(p).toHaveAttribute("marker-end");
    expect(container.querySelector("path[data-growing]")).toBeNull();
    expect(container.querySelector("text")).toHaveTextContent("Approved");
  });

  it("draws no arrow before its step, and no head while the line is still growing", () => {
    vi.useFakeTimers();
    const { container } = render(<ReplayCanvas graph={seq} live={false} />);
    fireEvent.click(screen.getByRole("button", { name: "Replay" }));
    expect(edges(container)).toHaveLength(0);
    act(() => { vi.advanceTimersByTime(1600); });
    const growing = container.querySelector("path[data-growing]")!;
    expect(growing).not.toBeNull();
    expect(growing).not.toHaveAttribute("marker-end");
    expect(edges(container)).toHaveLength(1);
    expect(container.querySelector(`[data-block-id="${growing.getAttribute("data-to")}"]`)).toBeNull();
    act(() => { vi.advanceTimersByTime(700); });
    expect(container.querySelector(`path[data-to="${growing.getAttribute("data-to")}"]`)).toHaveAttribute("marker-end");
    expect(container.querySelector("path[data-growing]")).toBeNull();
    expect(container.querySelector(`[data-block-id="${growing.getAttribute("data-to")}"]`)).not.toBeNull();
  });

  it("fades the not-taken branch in with its block, without growing it", () => {
    vi.useFakeTimers();
    const { container } = render(<ReplayCanvas graph={gated} live={false} />);
    fireEvent.click(screen.getByRole("button", { name: "Replay" }));
    act(() => { vi.advanceTimersByTime(3200); }); // beat 2: the fork, line phase
    expect(container.querySelector('path[data-growing][data-to="w"]')).not.toBeNull();
    expect(container.querySelector('path[data-edge="ghost"]')).toBeNull();
    expect(container.querySelector('[data-block-id="ghost:q"]')).toBeNull();
    act(() => { vi.advanceTimersByTime(700); }); // block phase
    const ghost = container.querySelector('path[data-edge="ghost"]')!;
    expect(ghost).not.toBeNull();
    expect(ghost).not.toHaveAttribute("data-growing");
    expect(ghost).toHaveAttribute("data-fading");
    expect(ghost).toHaveAttribute("marker-end");
    expect(container.querySelector('[data-block-id="ghost:q"]')).not.toBeNull();
  });

  it("keeps the block buttons out of hidden decoration, named in words", () => {
    render(<ReplayCanvas graph={gated} live={false} />);
    for (const h of document.querySelectorAll('[aria-hidden="true"]')) expect(h.querySelector("button")).toBeNull();
    const d = gated.blocks.find((b) => b.id === "d")!;
    expect(d.detail).toBe("Ledger");
    expect(screen.getByRole("button", { name: `Draft: ${d.title}, Ledger` })).toHaveAttribute("data-block-id", "d");
  });

  it("narrates the branch when a rejected gate ends the run", () => {
    const g = buildReplay([
      step("WRITE_STAGED", { resource: "ledger", count: 2 }, 1, 2, "d"), step("GATE_REQUESTED", { action: "post", summary: "Post?" }, null, 3, "q"),
      step("GATE_DECIDED", { action: "post", decision: "REJECTED" }, null, 60, "r"),
    ], []);
    render(<ReplayCanvas graph={g} live={false} />);
    const items = within(screen.getByRole("list", { name: "Steps in order" })).getAllByRole("listitem");
    expect(items[1]).toHaveTextContent("then Rejected. Not taken: Would have posted, Ledger");
  });

  it("draws the not-taken block as a plain shape, not a control", () => {
    const { container } = render(<ReplayCanvas graph={gated} live={false} />);
    const ghost = container.querySelector('[data-block-id="ghost:q"]')!;
    expect(ghost.tagName).not.toBe("BUTTON");
    expect(ghost).toHaveAttribute("aria-hidden", "true");
    expect(screen.queryByRole("button", { name: /Not taken/ })).toBeNull();
  });

  it("tells screen readers the steps and the branch in words", () => {
    render(<ReplayCanvas graph={gated} live={false} />);
    const list = screen.getByRole("list", { name: "Steps in order" });
    const items = within(list).getAllByRole("listitem");
    expect(items).toHaveLength(3);
    expect(items[1]).toHaveTextContent("Asks you: Post 2 entries?, Approved at");
    expect(items[1]).toHaveTextContent("then Approved. Not taken: Stops here, Nothing is posted");
  });

  it("reports progress as Step n of N", () => {
    render(<ReplayCanvas graph={seq} live={false} />);
    const bar = screen.getByRole("progressbar");
    expect(bar).toHaveAttribute("aria-valuetext", "Step 4 of 4");
    expect(bar).toHaveAttribute("aria-valuenow", "4");
    expect(bar).toHaveAttribute("aria-valuemax", "4");
    expect(screen.getByText("Step 4 of 4")).toBeInTheDocument();
  });

  it("disables replay controls on a live run", () => {
    render(<ReplayCanvas graph={seq} live />);
    for (const name of ["Replay", /^(Pause|Resume)$/, "Step back", "Step forward"]) expect(screen.getByRole("button", { name })).toBeDisabled();
    expect(screen.getByRole("button", { name: "0.5×" })).toBeEnabled();
    expect(screen.getByRole("button", { name: "1×" })).toHaveAttribute("aria-pressed", "true");
  });

  it("selects a step when its block is clicked", async () => {
    const onSelect = vi.fn();
    const { container, rerender } = render(<ReplayCanvas graph={seq} live={false} onSelect={onSelect} />);
    await userEvent.click(container.querySelector('[data-block-id="r"]')!);
    expect(onSelect).toHaveBeenCalledWith("r");
    rerender(<ReplayCanvas graph={seq} live={false} onSelect={onSelect} selectedId="r" />);
    expect(container.querySelector('[data-block-id="r"]')).toHaveAttribute("aria-pressed", "true");
    expect(container.querySelector('[data-block-id="n"]')).toHaveAttribute("aria-pressed", "false");
  });

  it("stops shrinking at 80% and scrolls instead", () => {
    vi.stubGlobal("ResizeObserver", class { constructor(private cb: () => void) {} observe() { this.cb(); } disconnect() {} });
    const cw = vi.spyOn(HTMLElement.prototype, "clientWidth", "get").mockReturnValue(400);
    const { container } = render(<ReplayCanvas graph={long(60)} live={false} />);
    expect((container.querySelector("[data-world]") as HTMLElement).style.transform).toContain("scale(0.8)");
    cw.mockRestore();
  });

  it("stays at the right end on a live run only if the user was there", () => {
    const sw = vi.spyOn(HTMLElement.prototype, "scrollWidth", "get").mockReturnValue(1000);
    const cw = vi.spyOn(HTMLElement.prototype, "clientWidth", "get").mockReturnValue(400);
    const box = (c: HTMLElement) => c.querySelector("[data-scroll]") as HTMLElement;
    const { container, rerender } = render(<ReplayCanvas graph={long(3)} live />);
    Object.defineProperty(box(container), "scrollLeft", { value: 600, writable: true, configurable: true });
    rerender(<ReplayCanvas graph={long(4)} live />);
    expect(box(container).scrollLeft).toBe(1000);
    box(container).scrollLeft = 0;
    rerender(<ReplayCanvas graph={long(5)} live />);
    expect(box(container).scrollLeft).toBe(0);
    sw.mockRestore(); cw.mockRestore();
  });

  it("grows a step that lands in an already-drawn beat (a write beside the not-taken branch)", () => {
    vi.useFakeTimers();
    const read = step("DATA_READ", { resource: "ledger", count: 2 }, 1, 2, "r");
    const ask = step("GATE_REQUESTED", { action: "post", summary: "Post?" }, null, 3, "q");
    const ok = step("GATE_DECIDED", { action: "post", decision: "APPROVED" }, null, 60, "a");
    const write = step("DATA_WRITE", { resource: "ledger", count: 2 }, 61, 62, "w");
    const { container, rerender } = render(<ReplayCanvas graph={buildReplay([read, ask], [])} live />);
    rerender(<ReplayCanvas graph={buildReplay([read, ask, ok], [])} live />);
    act(() => { vi.advanceTimersByTime(1600); });
    expect(container.querySelector('[data-block-id="ghost:q"]')).not.toBeNull();
    rerender(<ReplayCanvas graph={buildReplay([read, ask, ok, write], [])} live />);
    expect(container.querySelector('[data-block-id="w"]')).toBeNull();
    const line = container.querySelector('path[data-to="w"]')!;
    expect(line).toHaveAttribute("data-growing");
    expect(line).not.toHaveAttribute("marker-end");
    expect(container.querySelector('[data-block-id="ghost:q"]')).not.toBeNull(); // already drawn: stays
    expect(container.querySelector('path[data-edge="ghost"]')).toHaveAttribute("marker-end");
    act(() => { vi.advanceTimersByTime(700); });
    expect(container.querySelector('[data-block-id="w"]')).not.toBeNull();
    expect(container.querySelector('path[data-to="w"]')).toHaveAttribute("marker-end");
  });

  it("grows a parallel call that finishes after the poll that drew its partner", () => {
    vi.useFakeTimers();
    const note = step("AGENT_LOG", { message: "Reading" }, null, 1, "n");
    const a = step("DATA_READ", { resource: "bank" }, 2, 6, "a");
    const b = step("DATA_READ", { resource: "ledger" }, 3, 8, "b");
    const { container, rerender } = render(<ReplayCanvas graph={buildReplay([note, a], [])} live />);
    rerender(<ReplayCanvas graph={buildReplay([note, a, b], [])} live />);
    expect(container.querySelector('[data-block-id="a"]')).not.toBeNull();
    expect(container.querySelector('path[data-to="a"]')).toHaveAttribute("marker-end");
    expect(container.querySelector('path[data-to="a"]')).not.toHaveAttribute("data-growing");
    expect(container.querySelector('[data-block-id="b"]')).toBeNull();
    expect(container.querySelector('path[data-to="b"]')).toHaveAttribute("data-growing");
    expect(container.querySelector('path[data-to="b"]')).not.toHaveAttribute("marker-end");
    act(() => { vi.advanceTimersByTime(700); });
    expect(container.querySelector('[data-block-id="b"]')).not.toBeNull();
    expect(container.querySelector('path[data-to="b"]')).toHaveAttribute("marker-end");
  });

  it("grows a catch-up line at the pace the replay keeps, even when the run ends mid-beat", () => {
    vi.useFakeTimers();
    const { container, rerender } = render(<ReplayCanvas graph={long(1)} live />);
    const nine = long(9);
    rerender(<ReplayCanvas graph={nine} live />);
    const growing = () => container.querySelector("path[data-growing]") as SVGPathElement;
    expect(growing().style.animationDuration).toBe("150ms");
    rerender(<ReplayCanvas graph={nine} live={false} />);
    expect(growing().style.animationDuration).toBe("150ms");
  });
});
