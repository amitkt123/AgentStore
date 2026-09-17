import { backend } from "@/lib/backend";
import type { RunDetailResponse } from "@/lib/types";
import { RunConsole } from "./RunConsole";

export default async function RunPage({ params }: { params: Promise<{ runId: string }> }) {
  const { runId } = await params;
  const initial = await backend.get<RunDetailResponse>(`/api/v1/runs/${runId}`);
  return <RunConsole runId={runId} initial={initial} />;
}
