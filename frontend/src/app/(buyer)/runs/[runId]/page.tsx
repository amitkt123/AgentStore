import { backend } from "@/lib/backend";
import { getSession } from "@/lib/session";
import type { RunDetailResponse } from "@/lib/types";
import { RunConsole } from "./RunConsole";

export default async function RunPage({ params }: { params: Promise<{ runId: string }> }) {
  const { runId } = await params;
  const [initial, session] = await Promise.all([
    backend.get<RunDetailResponse>(`/api/v1/runs/${runId}`),
    getSession(),
  ]);
  return <RunConsole runId={runId} initial={initial} role={session?.role} />;
}
