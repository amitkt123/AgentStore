import Link from "next/link";
import { ApiError } from "@/lib/api/http";
import { getRunGates } from "@/lib/api/runs";
import { getSession } from "@/lib/session";
import { canDecideGates } from "@/lib/roles";
import { GateCard } from "@/features/gates/GateCard";
import { ErrorState } from "@/ui/ErrorState";

export default async function Page({ params }: { params: { runId: string; gateId: string } }) {
  const session = await getSession();
  try {
    const gates = await getRunGates(params.runId);
    const gate = gates.find((g) => g.gateId === params.gateId);
    if (!gate) return <ErrorState message="We can't find that request." />;
    return (
      <>
        <h1>Approval needed</h1>
        <GateCard runRef={params.runId} gate={gate} canDecide={!!session && canDecideGates(session.role)} />
        <p><Link href={`/runs/${params.runId}`}>See the whole run</Link></p>
      </>
    );
  } catch (e) {
    if (e instanceof ApiError) return <ErrorState message={e.status === 404 ? "We can't find that run." : e.message} />;
    throw e;
  }
}
