import { useCallback, useEffect, useRef, useState } from "react";

export const BEAT_MS = 1600, DRAW_MS = 700, CATCH_UP_MS = 300, CATCH_UP_AFTER = 5;
export type Speed = 0.5 | 1;
export interface Replay {
  visible: number;
  drawing: { beat: number; phase: "line" | "block" } | null;
  playing: boolean; speed: Speed;
  replay(): void; pause(): void; resume(): void; back(): void; next(): void; setSpeed(s: Speed): void;
}

interface State {
  visible: number;
  drawing: { beat: number; phase: "line" | "block" } | null;
  playing: boolean; speed: Speed;
  target: number; // playback runs until this many beats are visible
  live: boolean;  // catch-up pacing applies (beats that arrived while watching)
}
type Action =
  | { type: "grow"; beats: number }
  | { type: "replay" | "resume" | "next"; beats: number }
  | { type: "back" | "pause" | "block" | "end" }
  | { type: "speed"; speed: Speed };

const startBeat = (s: State): State => ({ ...s, visible: s.visible + 1, drawing: { beat: s.visible, phase: "line" }, playing: true });
const stop = (s: State): State => ({ ...s, playing: false, drawing: null });

function reduce(s: State, a: Action): State {
  switch (a.type) {
    case "grow": {
      if (a.beats < s.visible) return { ...stop(s), visible: a.beats, target: a.beats };
      const grown = { ...s, target: a.beats };
      if (s.playing || a.beats === s.visible) return grown;
      return startBeat({ ...grown, live: true });
    }
    case "replay": return a.beats > 0 ? startBeat({ ...s, visible: 0, target: a.beats, live: false }) : s;
    case "resume": {
      if (s.playing) return s;
      const t = { ...s, target: a.beats };
      if (s.drawing) return { ...t, playing: true };
      return s.visible < a.beats ? startBeat(t) : s;
    }
    case "pause": return s.playing ? { ...s, playing: false } : s;
    case "back": return { ...stop(s), visible: Math.max(1, s.visible - 1) };
    case "next": return s.visible < a.beats ? startBeat({ ...stop(s), target: s.visible + 1, live: false }) : stop(s);
    case "block": return s.drawing ? { ...s, drawing: { ...s.drawing, phase: "block" } } : s;
    case "end": return s.visible < s.target ? startBeat(s) : stop(s);
    case "speed": return { ...s, speed: a.speed };
  }
}

export function useReplay(beats: number, opts: { reducedMotion: boolean }): Replay {
  const [s, setS] = useState<State>(() => ({ visible: beats, drawing: null, playing: false, speed: 1, target: beats, live: false }));
  // State lives in a ref too so a timer can chain the next phase without waiting for a render.
  const ref = useRef(s);
  const timer = useRef<ReturnType<typeof setTimeout>>();
  const seen = useRef(beats);

  const arm = useCallback(() => {
    clearTimeout(timer.current);
    const { playing, drawing, speed, live, target, visible } = ref.current;
    if (!playing || !drawing) return;
    const catchUp = live && target - visible > CATCH_UP_AFTER;
    const beat = catchUp ? CATCH_UP_MS : BEAT_MS / speed;
    const line = catchUp ? CATCH_UP_MS / 2 : DRAW_MS / speed;
    const isLine = drawing.phase === "line";
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

  useEffect(() => {
    if (beats === seen.current) return;
    seen.current = beats;
    send({ type: "grow", beats });
  }, [beats, send]);
  useEffect(() => () => clearTimeout(timer.current), []);

  return {
    visible: s.visible, drawing: opts.reducedMotion ? null : s.drawing, playing: s.playing, speed: s.speed,
    replay: useCallback(() => send({ type: "replay", beats }), [send, beats]),
    pause: useCallback(() => send({ type: "pause" }), [send]),
    resume: useCallback(() => send({ type: "resume", beats }), [send, beats]),
    back: useCallback(() => send({ type: "back" }), [send]),
    next: useCallback(() => send({ type: "next", beats }), [send, beats]),
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
