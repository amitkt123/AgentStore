import type { CatalogListing } from "@/lib/api/types";

const parts = (v: string) => v.split(".").map((p) => Number.parseInt(p, 10) || 0);

function newer(a: string, b: string): boolean {
  const [x, y] = [parts(a), parts(b)];
  for (let i = 0; i < Math.max(x.length, y.length); i++) {
    if ((x[i] ?? 0) !== (y[i] ?? 0)) return (x[i] ?? 0) > (y[i] ?? 0);
  }
  return false;
}

/** The catalog lists every LIVE version; the store shows one card per agent, on its highest version. */
export function latestPerAgent(listings: CatalogListing[]): CatalogListing[] {
  const best = new Map<string, CatalogListing>();
  for (const l of listings) {
    const cur = best.get(l.agentId);
    if (!cur || newer(l.versionNumber, cur.versionNumber)) best.set(l.agentId, l);
  }
  return [...best.values()];
}
