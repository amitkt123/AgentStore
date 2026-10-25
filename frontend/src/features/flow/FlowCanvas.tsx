"use client";
import { useId, useLayoutEffect, useRef, useState } from "react";
import styles from "./FlowCanvas.module.css";
import { edgeMidpoint, edgePath, isSideways, type Box } from "./geometry";
import type { FlowGraph, FlowNode } from "./layout";
import type { BlockState } from "@/lib/api/types";

const STATE_TEXT: Record<BlockState, string> = {
  DONE: "Done", WAITING: "Waiting for you", FAILED: "Failed", SKIPPED: "Skipped", IDLE: "Not started",
};

type Props = { graph: FlowGraph; selectedId?: string; onSelect?: (id: string) => void; showState?: boolean; live?: boolean; caption?: boolean; endText?: string | null };

/** Measures every node and end box relative to the canvas, again whenever the canvas resizes. */
function useBoxes(root: React.RefObject<HTMLDivElement>, graph: FlowGraph): Map<string, Box> {
  const [boxes, setBoxes] = useState<Map<string, Box>>(new Map());
  useLayoutEffect(() => {
    const el = root.current;
    if (!el) return;
    const measure = () => {
      const o = el.getBoundingClientRect();
      const next = new Map<string, Box>();
      el.querySelectorAll<HTMLElement>("[data-node-id],[data-end]").forEach((n) => {
        const r = n.getBoundingClientRect();
        next.set(n.dataset.nodeId ?? n.dataset.end!, { x: r.left - o.left, y: r.top - o.top, w: r.width, h: r.height });
      });
      setBoxes((prev) => (JSON.stringify([...prev]) === JSON.stringify([...next]) ? prev : next));
    };
    measure();
    if (typeof ResizeObserver === "undefined") return;
    const ro = new ResizeObserver(measure);
    ro.observe(el);
    return () => ro.disconnect();
  }, [root, graph]);
  return boxes;
}

function Node({ n, selected, onSelect, showState }: { n: FlowNode; selected: boolean; onSelect?: (id: string) => void; showState: boolean }) {
  return (
    <button
      type="button"
      data-node-id={n.id}
      data-derived={n.derived || undefined}
      className={`${styles.node} ${styles[n.state]} ${selected ? styles.sel : ""}`}
      aria-pressed={selected}
      onClick={() => onSelect?.(n.id)}
    >
      <span className={styles.kind}>{n.kind}</span>
      <strong>{n.title}</strong>
      {n.body && <span className={styles.body}>{n.body}</span>}
      {n.metric && <span className={`${styles.metric} mono`}>{n.metric}</span>}
      {n.undeclared && <span className={styles.flag}>Not declared by the agent</span>}
      {showState && <span className={styles.state}>{STATE_TEXT[n.state]}</span>}
      {n.stage === "Asks you" && <span className="sr-only">, then Approved continues, Rejected stops</span>}
    </button>
  );
}

export function FlowCanvas({ graph, selectedId, onSelect, showState = true, live = false, caption = false, endText = null }: Props) {
  const root = useRef<HTMLDivElement>(null);
  const boxes = useBoxes(root, graph);
  const marker = `arrow-${useId().replace(/:/g, "")}`;
  const stateOf = new Map<string, BlockState>([...graph.columns.flatMap((c) => c.nodes), ...graph.offPath].map((n) => [n.id, n.state]));
  const endsReached = new Set(graph.ends.filter((e) => e.reached).map((e) => e.id));
  const node = (n: FlowNode) => <Node key={n.id} n={n} selected={selectedId === n.id} onSelect={onSelect} showState={showState} />;

  return (
    <div className={styles.wrap}>
      <div ref={root} className={styles.canvas}>
        <svg className={styles.edges} aria-hidden="true">
          <defs>
            <marker id={marker} viewBox="0 0 8 8" refX="7" refY="4" markerWidth="8" markerHeight="8" orient="auto-start-reverse">
              <path d="M 0 0 L 8 4 L 0 8 z" className={styles.head} />
            </marker>
          </defs>
          {graph.edges.map((e) => {
            const a = boxes.get(e.from), b = boxes.get(e.to);
            if (!a || !b) return null; // not measured yet: no arrow rather than one from the corner
            const d = edgePath(a, b);
            const active = e.to === graph.activeTo;
            const halted = e.kind === "rejected" && endsReached.has(e.to);
            const mid = edgeMidpoint(a, b);
            return (
              <g key={`${e.from}>${e.to}`}>
                <path
                  d={d}
                  data-edge={e.kind}
                  data-from={e.from}
                  data-to={e.to}
                  data-active={active || undefined}
                  data-stopped={halted || undefined}
                  className={`${styles.edge} ${styles[e.kind]} ${styles[`from${stateOf.get(e.from) ?? "IDLE"}`]} ${active ? styles.active : ""} ${halted ? styles.halted : ""}`}
                  markerEnd={`url(#${marker})`}
                />
                {(e.kind === "approved" || e.kind === "rejected") && (
                  e.kind === "approved"
                    ? <text x={mid.x} y={mid.y - 8} textAnchor="middle" className={styles.label}>Approved</text>
                    : isSideways(a, b)
                      ? <text x={mid.x} y={mid.y - 8} textAnchor="middle" className={styles.label}>Rejected</text>
                      : <text x={mid.x + 8} y={mid.y + 4} textAnchor="start" className={styles.label}>Rejected</text>
                )}
                {active && live && <circle className={styles.pulse} r={3} style={{ offsetPath: `path("${d}")` }} />}
              </g>
            );
          })}
        </svg>
        <ol className={styles.flow} aria-label="Flow">
          {graph.columns.map((c) => (
            <li key={c.stage} data-stage={c.stage} className={styles.col}>
              {c.nodes.map(node)}
              {c.stage === "Asks you" && graph.ends.map((e) => (
                <div key={e.id} data-end={e.id} className={`${styles.end} ${e.reached ? styles.reached : ""}`}>
                  {e.reached && endText ? endText : e.label}
                </div>
              ))}
            </li>
          ))}
          {graph.offPath.length > 0 && (
            <li aria-label="Off the declared path" className={styles.off}>{graph.offPath.map(node)}</li>
          )}
        </ol>
      </div>
      {caption && <p className={styles.caption}>The order is how Karya groups what this agent declared.</p>}
    </div>
  );
}

const MINI_WORD: Record<FlowNode["stage"], (n: number) => string> = {
  Reads: (n) => `reads ${n}`,
  Thinks: () => "thinks",
  Drafts: (n) => `drafts ${n}`,
  "Asks you": (n) => (n === 1 ? "asks you once" : `asks you ${n} times`),
  Writes: (n) => `writes ${n}`,
  Charges: () => "then charges",
};

export function miniLabel(graph: FlowGraph): string {
  const parts = graph.columns.map((c) => MINI_WORD[c.stage](c.nodes.length));
  if (parts.length === 1 && graph.columns[0].stage === "Charges") return "Charges";
  const text = parts.join(", ");
  return text.charAt(0).toUpperCase() + text.slice(1);
}

export function FlowMini({ graph }: { graph: FlowGraph }) {
  const nodes = graph.columns.flatMap((c) => c.nodes);
  return (
    <div role="img" aria-label={miniLabel(graph)} className={styles.mini}>
      {nodes.map((n, i) => (
        <span key={n.id} className={styles.miniStep}>
          {i > 0 && <i className={styles.miniLine} />}
          <b className={`${styles.miniDot} ${styles[n.state]}`} />
        </span>
      ))}
    </div>
  );
}
