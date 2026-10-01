import { listMyAgents } from "@/lib/api/agents";
import { ApiError } from "@/lib/api/http";
import { AgentsView } from "@/features/agents/AgentsView";
import { ErrorState } from "@/ui/ErrorState";

export default async function Page() {
  try {
    const agents = await listMyAgents();
    return (
      <>
        <h1>Agents</h1>
        <p>Your agents and their versions. Open a version to see what it declares and submit it.</p>
        <AgentsView agents={agents} />
      </>
    );
  } catch (e) {
    if (e instanceof ApiError) return <ErrorState message={e.message} />;
    throw e;
  }
}
