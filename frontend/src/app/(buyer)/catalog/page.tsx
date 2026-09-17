import { backend } from "@/lib/backend";
import type { CatalogListingResponse } from "@/lib/types";
import { CatalogList } from "./CatalogList";

export default async function CatalogPage() {
  const listings = await backend.get<CatalogListingResponse[]>("/api/v1/catalog");
  return (
    <div>
      <h1 className="font-serif text-2xl mb-2">Agents for finance work</h1>
      <p className="text-ink2 mb-6 max-w-2xl">
        Every agent here runs inside your workspace, touches only what its statement says it can.
      </p>
      <CatalogList listings={listings} />
    </div>
  );
}
