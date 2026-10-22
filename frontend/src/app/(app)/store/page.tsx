import { listCatalog } from "@/lib/api/catalog";
import { listMyConnections } from "@/lib/api/connections";
import { ApiError } from "@/lib/api/http";
import { StoreGrid } from "@/features/store/StoreGrid";
import { latestPerAgent } from "@/features/store/listings";
import { EmptyState } from "@/ui/EmptyState";
import { ErrorState } from "@/ui/ErrorState";

export default async function Page() {
  try {
    const [listings, connections] = await Promise.all([listCatalog(), listMyConnections().catch(() => [])]);
    const added = new Set(connections.filter((c) => c.status === "ACTIVE").map((c) => c.agentId));
    return (
      <>
        <h1>Agent store</h1>
        <p>Every agent shows what it will read, do and charge before you add it.</p>
        {listings.length === 0
          ? <EmptyState title="No agents are published yet" body="Check back soon." />
          : <StoreGrid listings={latestPerAgent(listings)} addedAgentIds={added} />}
      </>
    );
  } catch (e) {
    if (e instanceof ApiError) return <ErrorState message={e.message} />;
    throw e;
  }
}
