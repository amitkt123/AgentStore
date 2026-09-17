import { NextRequest, NextResponse } from "next/server";
import { backend, BackendError } from "@/lib/backend";
import type { RunSummaryResponse } from "@/lib/types";

export async function GET(request: NextRequest) {
  const status = request.nextUrl.searchParams.get("status");
  const path = status ? `/api/v1/runs/mine?status=${status}` : "/api/v1/runs/mine";
  try {
    const runs = await backend.get<RunSummaryResponse[]>(path);
    return NextResponse.json(runs);
  } catch (e) {
    if (e instanceof BackendError) return NextResponse.json(e.body, { status: e.status });
    throw e;
  }
}
