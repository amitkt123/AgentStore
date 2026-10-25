import { useCallback, useEffect, useRef, useState } from "react";

export const BEAT_MS = 1600, DRAW_MS = 700, CATCH_UP_MS = 300, CATCH_UP_AFTER = 5;
export type Speed = 0.5 | 1;
export interface Replay {
  visible: number;
  drawing: { beat: number; phase: "line" | "block" } | null;
  /** Blocks already on screen when a drawn beat gained a step: they stay put while it is re-drawn. */
  settled: ReadonlySet<string>;
  /** How long the current beat's line takes to grow, in ms. */
  lineMs: number;
  /** The current beat runs at catch-up pace. */
  catchUp: boolean;
  playing: boolean; speed: Speed;
  replay(): void; pause(): void; resume(): void; back(): void; next(): void; setSpeed(s: Speed): void;
}

type Beats = string[][]; // block ids per beat
interface State {
  ids: Beats;
  visible: number;
  drawing: { beat: number; phase: "line" | "block" } | null;
  settled: ReadonlySet<string>;
  playing: boolean; speed: Speed;
  target: number; // playback runs until this many beats are visible
  live: boolean;  // catch-up pacing applies (beats that arrived while watching)
  fast: boolean;  // the current beat was started at catch-up pace
}
type Action =
  | { type: "grow"; ids: Beats }
  | { type: "replay" | "resume" | "next" | "back" | "pause" | "block" | "end" }
  | { type: "speed"; speed: Speed };

const NONE: ReadonlySet<string> = new Set();
const startBeat = (s: State): State => {
  const visible = s.visible + 1;
  return { ...s, visible, drawing: { beat: s.visible, phase: "line" }, playing: true, fast: s.live && s.target - visible > CATCH_UP_AFTER };
};
const stop = (s: State): State => ({ ...s, playing: false, drawing: null, settled: NONE });

/** Ids on screen now: settled ones, plus every drawn beat except one still growing its line. */
const onScreen = (s: State): Set<string> => {
  const out = new Set(s.settled);
  s.ids.slice(0, s.visible).forEach((beat, n) => {
    if (s.drawing?.beat === n && s.drawing.phase === "line") return;
    beat.forEach((id) => out.add(id));
  });
  return out;
};

function reduce(s: State, a: Action): State {
  const beats = s.ids.length;
  switch (a.type) {
    case "grow": {
      const n = a.ids.length;
      if (n < s.visible) return { ...stop(s), ids: a.ids, visible: n, target: n };
      // A step landing in a beat already drawn re-draws from that beat; what is on screen stays.
      const shown = onScreen(s);
      const k = a.ids.slice(0, s.visible).findIndex((beat, i) =>
        beat.some((id) => !shown.has(id)) && !(s.drawing?.beat === i && s.drawing.phase === "line"));
      const grown = { ...s, ids: a.ids, target: n };
      if (k >= 0) return startBeat({ ...grown, visible: k, settled: shown, live: true });
      if (s.playing || n === s.visible) return grown;
      return startBeat({ ...grown, live: true });
    }
    case "replay": return beats > 0 ? startBeat({ ...s, visible: 0, settled: NONE, target: beats, live: false }) : s;
    case "resume": {
      if (s.playing) return s;
      const t = { ...s, target: beats };
      if (s.drawing) return { ...t, playing: true };
      return s.visible < beats ? startBeat(t) : s;
    }
    case "pause": return s.playing ? { ...s, playing: false } : s;
    case "back": return { ...stop(s), visible: Math.max(1, s.visible - 1) };
    case "next": return s.visible < beats ? startBeat({ ...stop(s), target: s.visible + 1, live: false }) : stop(s);
    case "block": return s.drawing ? { ...s, drawing: { ...s.drawing, phase: "block" } } : s;
    case "end": return s.visible < s.target ? startBeat(s) : stop(s);
    case "speed": return { ...s, speed: a.speed };
  }
}

const lineOf = (s: State) => (s.fast ? CATCH_UP_MS / 2 : DRAW_MS / s.speed);

export function useReplay(ids: Beats, opts: { reducedMotion: boolean }): Replay {
  const [s, setS] = useState<State>(() => ({ ids, visible: ids.length, drawing: null, settled: NONE, playing: false, speed: 1, target: ids.length, live: false, fast: false }));
  // State lives in a ref too so a timer can chain the next phase without waiting for a render.
  const ref = useRef(s);
  const timer = useRef<ReturnType<typeof setTimeout>>();
  const sig = ids.map((b) => b.join(",")).join("|");
  const seen = useRef(sig);

  const arm = useCallback(() => {
    clearTimeout(timer.current);
    const cur = ref.current;
    if (!cur.playing || !cur.drawing) return;
    const beat = cur.fast ? CATCH_UP_MS : BEAT_MS / cur.speed;
    const line = lineOf(cur);
    const isLine = cur.drawing.phase === "line";
    timer.current = setTimeout(() => { send(isLine ? { type: "block" } : { type: "end" }); }, isLine ? line : beat - line);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);
  const send = useCallback((a: Action) => {
    const key = (x: State) => `${x.playing}${x.drawing?.beat}${x.drawing?.phase}${x.speed}`;
    const before = key(ref.current);
    ref.current = reduce(ref.current, a);
    setS(ref.current);
    if (key(ref.current) !== before) arm(); // else the running timer keeps its elapsed time
  }, [arm]);

  const idsRef = useRef(ids);
  idsRef.current = ids;
  useEffect(() => {
    if (sig === seen.current) return;
    seen.current = sig;
    send({ type: "grow", ids: idsRef.current });
  }, [sig, send]);
  useEffect(() => () => clearTimeout(timer.current), []);

  return {
    visible: s.visible, drawing: opts.reducedMotion ? null : s.drawing, playing: s.playing, speed: s.speed,
    settled: s.settled, lineMs: lineOf(s), catchUp: s.fast,
    replay: useCallback(() => send({ type: "replay" }), [send]),
    pause: useCallback(() => send({ type: "pause" }), [send]),
    resume: useCallback(() => send({ type: "resume" }), [send]),
    back: useCallback(() => send({ type: "back" }), [send]),
    next: useCallback(() => send({ type: "next" }), [send]),
    setSpeed: useCallback((speed: Speed) => send({ type: "speed", speed }), [send]),
  };
}

const QUERY = "(prefers-reduced-motion: reduce)";
export function usePrefersReducedMotion(): boolean {
  const [reduced, setReduced] = useState(() => typeof window !== "undefined" && !!window.matchMedia?.(QUERY).matches);
  useEffect(() => {
    const mq = window.matchMedia?.(QUERY);
    if (!mq) return;
    const on = () => setReduced(mq.matches);
    on();
    mq.addEventListener?.("change", on);
    return () => mq.removeEventListener?.("change", on);
  }, []);
  return reduced;
}
