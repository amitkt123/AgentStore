import Link from "next/link";
import { ApiError } from "@/lib/api/http";
import { getRunGates } from "@/lib/api/runs";
import { getSession } from "@/lib/session";
import { canDecideGates } from "@/lib/roles";
import { GateCard } from "@/features/gates/GateCard";
import { ErrorState } from "@/ui/ErrorState";

// The first segment is named `token` because Next needs one slug name per level and /approve/[token] serves emailed links;
// on this older route it is the run reference (e.g. R-12).
export default async function Page({ params }: { params: { token: string; gateId: string } }) {
  const runId = params.token;
  const session = await getSession();
  try {
    const gates = await getRunGates(runId);
    const gate = gates.find((g) => g.gateId === params.gateId);
    if (!gate) return <ErrorState message="We can't find that request." />;
    return (
      <>
        <h1>Approval needed</h1>
        <GateCard runRef={runId} gate={gate} canDecide={!!session && canDecideGates(session.role)} />
        <p><Link href={`/runs/${runId}`}>See the whole run</Link></p>
      </>
    );
  } catch (e) {
    if (e instanceof ApiError) return <ErrorState message={e.status === 404 ? "We can't find that run." : e.message} />;
    throw e;
  }
}
