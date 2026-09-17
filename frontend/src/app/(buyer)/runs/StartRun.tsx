import type { ConnectionSummaryResponse } from "@/lib/types";
import { triggerRunAction } from "./actions";

export function StartRun({ connections }: { connections: ConnectionSummaryResponse[] }) {
  const active = connections.filter((c) => c.status === "ACTIVE");
  if (active.length === 0) return null;
  return (
    <div className="mb-6 border border-line rounded-lg p-4">
      <h2 className="font-semibold text-sm mb-3">Start a run</h2>
      <div className="flex flex-col gap-2">
        {active.map((c) => (
          <form key={c.id} action={triggerRunAction.bind(null, c.id)}>
            <button type="submit" className="flex justify-between w-full text-left px-3 py-2 border border-line rounded-md hover:bg-paper2">
              <span>{c.agentName}</span>
              <span className="text-ink2 text-sm">Run now</span>
            </button>
          </form>
        ))}
      </div>
    </div>
  );
}
