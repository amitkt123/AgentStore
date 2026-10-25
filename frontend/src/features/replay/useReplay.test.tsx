import { act, renderHook } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { useReplay } from "./useReplay";

/** n beats, one block each: [["b0"], ["b1"], ...]. */
const ids = (n: number) => Array.from({ length: n }, (_, i) => [`b${i}`]);
const mount = (beats: number | string[][], reducedMotion = false) =>
  renderHook(({ n }) => useReplay(typeof n === "number" ? ids(n) : n, { reducedMotion }), { initialProps: { n: beats } });
const tick = (ms: number) => act(() => { vi.advanceTimersByTime(ms); });

describe("useReplay", () => {
  beforeEach(() => { vi.useFakeTimers(); });
  afterEach(() => { vi.useRealTimers(); });

  it("starts fully drawn and still", () => {
    const { result } = mount(4);
    expect(result.current).toMatchObject({ visible: 4, drawing: null, playing: false });
  });

  it("replays beat by beat: line first, then the block", () => {
    const { result } = mount(3);
    act(() => result.current.replay()); tick(0);
    expect(result.current).toMatchObject({ visible: 1, drawing: { beat: 0, phase: "line" } });
    tick(700); expect(result.current.drawing).toEqual({ beat: 0, phase: "block" });
    tick(900); expect(result.current.visible).toBe(2);
    tick(3200); expect(result.current).toMatchObject({ visible: 3, drawing: null, playing: false });
  });

  it("goes half as fast at 0.5×", () => {
    const { result } = mount(3);
    act(() => result.current.setSpeed(0.5));
    act(() => result.current.replay()); tick(0);
    tick(1399); expect(result.current.drawing).toEqual({ beat: 0, phase: "line" });
    tick(1); expect(result.current.drawing).toEqual({ beat: 0, phase: "block" });
    tick(1799); expect(result.current.visible).toBe(1);
    tick(1); expect(result.current.visible).toBe(2);
  });

  it("pauses and resumes on the same beat", () => {
    const { result } = mount(3);
    act(() => result.current.replay()); tick(1600);
    expect(result.current.visible).toBe(2);
    act(() => result.current.pause()); tick(10000);
    expect(result.current).toMatchObject({ visible: 2, playing: false });
    act(() => result.current.resume()); tick(1600);
    expect(result.current.visible).toBe(3);
    tick(1600); expect(result.current).toMatchObject({ visible: 3, drawing: null, playing: false });
  });

  it("steps back without animating and forward with it", () => {
    const { result } = mount(4);
    act(() => result.current.back());
    expect(result.current).toMatchObject({ visible: 3, drawing: null, playing: false });
    act(() => result.current.next());
    expect(result.current).toMatchObject({ visible: 4, drawing: { beat: 3, phase: "line" } });
    tick(1600); expect(result.current).toMatchObject({ visible: 4, drawing: null, playing: false });
    act(() => result.current.next()); expect(result.current.drawing).toBeNull();
  });

  it("never steps back below one beat", () => {
    const { result } = mount(2);
    act(() => result.current.back()); act(() => result.current.back()); act(() => result.current.back());
    expect(result.current.visible).toBe(1);
  });

  it("plays steps that arrive later, one beat at a time", () => {
    const { result, rerender } = mount(2);
    rerender({ n: 4 });
    expect(result.current).toMatchObject({ visible: 3, drawing: { beat: 2, phase: "line" }, playing: true });
    tick(1599); expect(result.current.visible).toBe(3);
    tick(1); expect(result.current.visible).toBe(4);
    tick(1600); expect(result.current).toMatchObject({ visible: 4, drawing: null, playing: false });
  });

  it("keeps the beat clock when more beats arrive mid-beat", () => {
    const { result, rerender } = mount(3);
    act(() => result.current.replay()); tick(1600); tick(1000);
    expect(result.current.visible).toBe(2);
    rerender({ n: 4 });
    tick(599); expect(result.current.visible).toBe(2);
    tick(1); expect(result.current.visible).toBe(3);
  });

  it("catches up quickly when far behind", () => {
    const { result, rerender } = mount(1);
    rerender({ n: 9 });
    expect(result.current.visible).toBe(2);
    tick(150); expect(result.current.drawing).toEqual({ beat: 1, phase: "block" });
    tick(150); expect(result.current.visible).toBe(3);
    tick(300); expect(result.current.visible).toBe(4);
  });

  it("does nothing when polling brings the same beats again", () => {
    const { result, rerender } = mount(4);
    rerender({ n: 4 });
    expect(result.current).toMatchObject({ visible: 4, drawing: null, playing: false });
    expect(vi.getTimerCount()).toBe(0);
  });

  it("clamps to fewer beats without animating", () => {
    const { result, rerender } = mount(4);
    rerender({ n: 2 });
    expect(result.current).toMatchObject({ visible: 2, drawing: null, playing: false });
    expect(vi.getTimerCount()).toBe(0);
  });

  it("shows whole beats with reduced motion", () => {
    const { result } = mount(3, true);
    act(() => result.current.replay()); tick(0);
    expect(result.current).toMatchObject({ visible: 1, drawing: null });
    tick(1600); expect(result.current).toMatchObject({ visible: 2, drawing: null });
  });

  it("clears its timers on unmount", () => {
    const { result, unmount } = mount(3);
    act(() => result.current.replay());
    unmount();
    expect(vi.getTimerCount()).toBe(0);
  });

  it("re-draws a beat that gains a step, keeping what was already drawn", () => {
    const { result, rerender } = mount([["a"], ["b"]]);
    rerender({ n: [["a"], ["b", "c"]] });
    expect(result.current).toMatchObject({ visible: 2, drawing: { beat: 1, phase: "line" }, playing: true });
    expect([...result.current.settled].sort()).toEqual(["a", "b"]);
    tick(700); expect(result.current.drawing).toEqual({ beat: 1, phase: "block" });
    tick(900); expect(result.current).toMatchObject({ visible: 2, drawing: null, playing: false });
  });

  it("re-draws from the earliest drawn beat that gains a step, then plays the new beats", () => {
    const { result, rerender } = mount([["a"], ["b"], ["c"]]);
    rerender({ n: [["a", "x"], ["b"], ["c"], ["d"]] });
    expect(result.current).toMatchObject({ visible: 1, drawing: { beat: 0, phase: "line" } });
    expect([...result.current.settled].sort()).toEqual(["a", "b", "c"]);
    tick(1600 * 3); expect(result.current.visible).toBe(4);
    tick(1600); expect(result.current).toMatchObject({ visible: 4, drawing: null, playing: false });
  });

  it("re-draws a beat that gains a step while its block is rising", () => {
    const { result, rerender } = mount([["a"]]);
    rerender({ n: [["a"], ["b"]] }); tick(700);
    expect(result.current.drawing).toEqual({ beat: 1, phase: "block" });
    rerender({ n: [["a"], ["b", "c"]] });
    expect(result.current.drawing).toEqual({ beat: 1, phase: "line" });
    expect(result.current.settled.has("b")).toBe(true);
  });

  it("does not re-draw a beat still in its line phase", () => {
    const { result, rerender } = mount([["a"]]);
    rerender({ n: [["a"], ["b"]] }); tick(300);
    rerender({ n: [["a"], ["b", "c"]] });
    tick(400); expect(result.current.drawing).toEqual({ beat: 1, phase: "block" });
  });

  it("states the line duration it paces by", () => {
    const { result, rerender } = mount(1);
    expect(result.current.lineMs).toBe(700);
    act(() => result.current.setSpeed(0.5)); expect(result.current.lineMs).toBe(1400);
    act(() => result.current.setSpeed(1));
    rerender({ n: 9 });
    expect(result.current.lineMs).toBe(150);
  });
});
