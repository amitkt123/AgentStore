import Link from "next/link";
import { listCatalog } from "@/lib/api/catalog";
import { ApiError } from "@/lib/api/http";
import { EmptyState } from "@/ui/EmptyState";
import { ErrorState } from "@/ui/ErrorState";

export default async function Page() {
  try {
    const listings = await listCatalog();
    return (
      <>
        <h1>Agent store</h1>
        <p>Every agent shows what it will read, do and charge before you add it.</p>
        {listings.length === 0 ? (
          <EmptyState title="No agents are published yet" body="Check back soon." />
        ) : (
          <ul style={{ listStyle: "none", padding: 0, display: "grid", gap: 12, gridTemplateColumns: "repeat(auto-fill, minmax(260px, 1fr))" }}>
            {listings.map((l) => (
              <li key={l.versionId} style={{ background: "var(--s)", border: "1px solid var(--l)", borderRadius: 12, padding: 16 }}>
                <Link href={`/store/${l.agentId}/${l.versionId}`}><strong>{l.agentName}</strong></Link>
                <div style={{ color: "var(--ink3)", fontSize: 13 }}>{l.category} · v{l.versionNumber}</div>
              </li>
            ))}
          </ul>
        )}
      </>
    );
  } catch (e) {
    if (e instanceof ApiError) return <ErrorState message={e.message} />;
    throw e;
  }
}
