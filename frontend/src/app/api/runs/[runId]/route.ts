import { NextRequest, NextResponse } from "next/server";
import { backend, BackendError } from "@/lib/backend";
import type { RunDetailResponse } from "@/lib/types";

export async function GET(_request: NextRequest, { params }: { params: Promise<{ runId: string }> }) {
  const { runId } = await params;
  try {
    const run = await backend.get<RunDetailResponse>(`/api/v1/runs/${runId}`);
    return NextResponse.json(run);
  } catch (e) {
    if (e instanceof BackendError) return NextResponse.json(e.body, { status: e.status });
    throw e;
  }
}
