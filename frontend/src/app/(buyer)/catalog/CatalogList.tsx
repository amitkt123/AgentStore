import Link from "next/link";
import type { CatalogListingResponse } from "@/lib/types";

export function CatalogList({ listings }: { listings: CatalogListingResponse[] }) {
  if (listings.length === 0) return <p className="text-muted py-8">No agents are live yet.</p>;
  return (
    <div className="border-t border-line">
      {listings.map((listing) => (
        <Link
          key={listing.versionId}
          href={`/agents/${listing.agentId}/versions/${listing.versionId}`}
          className="grid grid-cols-[1.6fr_1fr_auto] gap-4 items-center py-4 border-b border-line hover:bg-paper2"
        >
          <div>
            <div className="font-semibold">{listing.agentName}</div>
            <div className="text-sm text-ink2">{listing.category}</div>
          </div>
          <div className="text-sm text-ink2">v{listing.versionNumber}</div>
          <span className="text-sm font-medium">View</span>
        </Link>
      ))}
    </div>
  );
}
