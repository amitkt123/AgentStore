import type { RunListItem } from "@/lib/api/types";

export interface Bar { id: string; displayId: string; leftPct: number; widthPct: number }
export interface Lane { agent: string; bars: Bar[] }

const sameDay = (a: number, b: number) => new Date(a).toDateString() === new Date(b).toDateString();
const hourOf = (ms: number) => new Date(ms).getHours() + new Date(ms).getMinutes() / 60;

export function layoutBars(items: RunListItem[], nowMs: number): { lanes: Lane[]; startHour: number; endHour: number } {
  const today = items.filter((r) => r.startedAt && sameDay(new Date(r.startedAt).getTime(), nowMs));
  if (today.length === 0) return { lanes: [], startHour: 0, endHour: 0 };
  const spans = today.map((r) => {
    const s = new Date(r.startedAt!).getTime();
    const e = r.completedAt ? new Date(r.completedAt).getTime() : nowMs;
    return { r, s, e: Math.max(e, s) };
  });
  const startHour = Math.floor(Math.min(...spans.map((x) => hourOf(x.s))));
  let endHour = Math.ceil(Math.max(...spans.map((x) => hourOf(x.e))));
  if (endHour - startHour < 3) endHour = startHour + 3;
  const total = endHour - startHour;
  const lanes = new Map<string, Bar[]>();
  for (const { r, s, e } of spans) {
    const left = ((hourOf(s) - startHour) / total) * 100;
    const width = Math.max(((hourOf(e) - hourOf(s)) / total) * 100, 1.5);
    const bars = lanes.get(r.agent) ?? [];
    bars.push({ id: r.id, displayId: r.displayId, leftPct: left, widthPct: width });
    lanes.set(r.agent, bars);
  }
  return { lanes: [...lanes].map(([agent, bars]) => ({ agent, bars })), startHour, endHour };
}
