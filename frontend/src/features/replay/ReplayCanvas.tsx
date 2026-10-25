"use client";
import { useId, useLayoutEffect, useMemo, useRef, useState } from "react";
import { Glass } from "@/ui/Glass";
import styles from "./ReplayCanvas.module.css";
import type { ReplayBlock, ReplayEdge, ReplayGraph } from "./graph";
import { elbowPath, placeReplay } from "./place";
import { CATCH_UP_AFTER, CATCH_UP_MS, DRAW_MS, usePrefersReducedMotion, useReplay } from "./useReplay";

type Props = { graph: ReplayGraph; live: boolean; selectedId?: string; onSelect?: (id: string) => void };

const MIN_SCALE = 0.8, DOT = 22, ENTER_MS = 600;
const atEnd = (el: HTMLElement) => el.scrollLeft + el.clientWidth >= el.scrollWidth - 2;

/** Sets the dash length a growing line animates from; jsdom has no getTotalLength. */
const measure = (p: SVGPathElement | null) => {
  if (!p) return;
  const len = typeof p.getTotalLength === "function" ? p.getTotalLength() : 0;
  p.style.setProperty("--len", String(len || 10000));
};

const say = (b: ReplayBlock) => `${b.label}: ${b.title}${b.detail ? `, ${b.detail}` : ""}`;

export function ReplayCanvas({ graph, live, selectedId, onSelect }: Props) {
  const reducedMotion = usePrefersReducedMotion();
  const r = useReplay(graph.beats, { reducedMotion });
  const placed = useMemo(() => placeReplay(graph), [graph]);
  const uid = useId().replace(/:/g, "");
  const head = `rh-${uid}`, ghostHead = `rg-${uid}`;

  // Fit: shrink to the panel, never below 80%; past that the panel scrolls.
  const box = useRef<HTMLDivElement>(null);
  const [k, setK] = useState(1);
  useLayoutEffect(() => {
    const el = box.current;
    if (!el) return;
    const fit = () => { const w = el.clientWidth; setK(w ? Math.max(MIN_SCALE, Math.min(1, w / placed.width)) : 1); };
    fit();
    if (typeof ResizeObserver === "undefined") return;
    const ro = new ResizeObserver(fit);
    ro.observe(el);
    return () => ro.disconnect();
  }, [placed.width]);

  // Live: read whether the user sits at the right end before the new content lands, then keep them there.
  const seenBeats = useRef(graph.beats);
  const stick = useRef(false);
  if (graph.beats !== seenBeats.current && box.current) stick.current = live && atEnd(box.current);
  useLayoutEffect(() => {
    if (graph.beats === seenBeats.current) return;
    seenBeats.current = graph.beats;
    if (stick.current && box.current) box.current.scrollLeft = box.current.scrollWidth;
    stick.current = false;
  }, [graph.beats]);

  const { visible, drawing, speed } = r;
  const lineOf = (beat: number) => drawing?.beat === beat && drawing.phase === "line";
  const shown = (b: ReplayBlock) => b.beat < visible && !lineOf(b.beat);
  const catchUp = live && graph.beats - visible > CATCH_UP_AFTER;
  const drawMs = catchUp ? CATCH_UP_MS / 2 : DRAW_MS / speed;
  const enterMs = catchUp ? CATCH_UP_MS / 2 : ENTER_MS / speed;
  const beatOf = new Map(graph.blocks.map((b) => [b.id, b.beat]));
  const blocks = graph.blocks.filter(shown);
  const current = Math.max(-1, ...blocks.filter((b) => b.tone !== "ghost").map((b) => b.beat));

  const sr = graph.blocks.filter((b) => b.beat < visible && b.tone !== "ghost").map((b) => {
    const ghost = graph.blocks.find((g) => g.id === `ghost:${b.id}` && g.beat < visible);
    const taken = graph.edges.find((e) => e.from === b.id && !e.ghost && e.label)?.label;
    return { id: b.id, text: ghost && taken ? `${say(b)}, then ${taken}. Not taken: ${ghost.title}${ghost.detail ? `, ${ghost.detail}` : ""}` : say(b) };
  });

  const edge = (e: ReplayEdge) => {
    const beat = beatOf.get(e.to) ?? 0;
    const a = placed.boxes.get(e.from), b = placed.boxes.get(e.to);
    if (beat >= visible || !a || !b) return null; // an arrow never exists before its step
    const { d, labelAt } = elbowPath(a, b);
    const growing = !e.ghost && lineOf(beat);
    const fading = e.ghost && drawing?.beat === beat;
    return (
      <g key={`${e.from}>${e.to}`}>
        <path
          ref={growing ? measure : undefined}
          d={d}
          data-edge={e.ghost ? "ghost" : "taken"}
          data-from={e.from}
          data-to={e.to}
          data-growing={growing || undefined}
          data-fading={fading || undefined}
          className={`${styles.edge} ${e.ghost ? styles.ghostEdge : ""} ${growing ? styles.growing : ""} ${fading ? styles.fade : ""}`}
          style={growing || fading ? { animationDuration: `${drawMs}ms` } : undefined}
          markerEnd={growing ? undefined : `url(#${e.ghost ? ghostHead : head})`}
        />
        {e.label && !growing && (
          <text x={labelAt.x} y={labelAt.y} textAnchor={labelAt.anchor} className={`${styles.label} ${fading ? styles.fade : ""}`}
            style={fading ? { animationDuration: `${drawMs}ms` } : undefined}>{e.label}</text>
        )}
      </g>
    );
  };

  const n = visible, total = graph.beats;
  const busy = live;
  return (
    <section aria-label="Run replay" className={styles.panel} style={{ backgroundSize: `${DOT * k}px ${DOT * k}px` }}>
      <Glass level={2} className={styles.controls} role="group" aria-label="Replay controls">
        <button type="button" className={styles.btn} disabled={busy || total === 0} onClick={r.replay}>Replay</button>
        <button type="button" className={styles.btn} disabled={busy || (!r.playing && !drawing && visible >= total)} onClick={r.playing ? r.pause : r.resume}>
          {r.playing ? "Pause" : "Resume"}
        </button>
        <button type="button" className={styles.btn} disabled={busy || visible <= 1} onClick={r.back}>Step back</button>
        <button type="button" className={styles.btn} disabled={busy || visible >= total} onClick={r.next}>Step forward</button>
        <button type="button" className={styles.btn} aria-pressed={speed === 0.5} onClick={() => r.setSpeed(0.5)}>0.5×</button>
        <button type="button" className={styles.btn} aria-pressed={speed === 1} onClick={() => r.setSpeed(1)}>1×</button>
        <div role="progressbar" aria-label="Replay progress" aria-valuemin={0} aria-valuenow={n} aria-valuemax={total}
          aria-valuetext={`Step ${n} of ${total}`} className={styles.bar}>
          <i style={{ width: `${total ? (n / total) * 100 : 0}%` }} />
        </div>
        <span className={styles.now} aria-hidden="true">Step {n} of {total}</span>
      </Glass>

      <div ref={box} className={styles.scroll} data-scroll="">
        <div className={styles.spacer} style={{ width: placed.width * k, height: placed.height * k }}>
          <div data-world="" aria-hidden="true" className={styles.world}
            style={{ width: placed.width, height: placed.height, transform: `scale(${k})` }}>
            {placed.lanes.map((l, i) => (
              <div key={l.lane} className={`${styles.lane} ${i % 2 ? styles.alt : ""}`} style={{ top: l.top, height: l.height }}>
                <span>{l.lane}</span>
              </div>
            ))}
            <svg className={styles.edges} width={placed.width} height={placed.height} aria-hidden="true">
              <defs>
                <marker id={head} viewBox="0 0 8 8" refX="8" refY="4" markerWidth="11" markerHeight="11" markerUnits="userSpaceOnUse" orient="auto">
                  <path d="M0 0L8 4L0 8z" className={styles.head} />
                </marker>
                <marker id={ghostHead} viewBox="0 0 8 8" refX="8" refY="4" markerWidth="11" markerHeight="11" markerUnits="userSpaceOnUse" orient="auto">
                  <path d="M0 0L8 4L0 8z" className={styles.ghostHead} />
                </marker>
              </defs>
              {graph.edges.map(edge)}
            </svg>
            {blocks.map((b) => {
              const p = placed.boxes.get(b.id)!;
              const entering = drawing?.beat === b.beat;
              return (
                <button
                  key={b.id}
                  type="button"
                  data-block-id={b.id}
                  aria-pressed={selectedId === b.id}
                  className={`${styles.block} ${styles[b.tone]} ${b.beat === current && b.tone !== "ghost" ? styles.current : ""} ${selectedId === b.id ? styles.sel : ""} ${entering ? styles.enter : ""}`}
                  style={{ left: p.x, top: p.y, width: p.w, minHeight: p.h, ...(entering ? { animationDuration: `${enterMs}ms` } : {}) }}
                  onClick={() => onSelect?.(b.id)}
                >
                  <span className={styles.top}>
                    {b.time && <span className={`${styles.time} mono`}>{b.time}</span>}
                    <span className={styles.kind}>{b.label}</span>
                  </span>
                  <strong>{b.title}</strong>
                  {b.detail && <span className={`${styles.detail} ${b.tone === "waiting" ? styles.warn : ""}`}>{b.detail}</span>}
                  {b.metric && <span className={`${styles.metric} mono`}>{b.metric}</span>}
                </button>
              );
            })}
          </div>
        </div>
      </div>

      <ol className="sr-only" aria-label="Steps in order">
        {sr.map((s) => <li key={s.id}>{s.text}</li>)}
      </ol>
    </section>
  );
}
