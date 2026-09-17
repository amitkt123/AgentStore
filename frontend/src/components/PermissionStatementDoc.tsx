import { formatInr } from "@/lib/format";
import type { Manifest } from "@/lib/types";
import { ScopeToggleGrant } from "./ScopeToggleGrant";

interface Props {
  manifest: Manifest;
  mode: "listing" | "grant" | "preview";
  onGrantChange?: (grantedResources: string[]) => void;
}

export function PermissionStatementDoc({ manifest, mode, onGrantChange }: Props) {
  const reads = manifest.scopes.filter((s) => s.type === "READ");
  const writes = manifest.scopes.filter((s) => s.type === "WRITE");

  return (
    <aside className="bg-doc border border-line rounded p-6" aria-label="Permission statement">
      <h3 className="font-serif text-lg">{manifest.name}</h3>
      <p className="font-serif italic text-ink2 text-sm mb-4">What this agent may do</p>

      {mode === "grant" ? (
        <ScopeToggleGrant scopes={manifest.scopes} onChange={onGrantChange ?? (() => {})} />
      ) : (
        <dl>
          <dt className="font-serif text-xs text-muted mt-4 mb-1 pb-1 border-b border-dotted border-line">Reads</dt>
          {reads.map((s) => (
            <dd key={s.resource} className="flex justify-between text-sm py-0.5">
              <span>{s.resource}</span>
              <span className="text-ink2">{s.description}</span>
            </dd>
          ))}
          <dt className="font-serif text-xs text-muted mt-4 mb-1 pb-1 border-b border-dotted border-line">Writes</dt>
          {writes.length === 0 ? (
            <dd className="text-sm py-0.5 text-muted line-through">nothing</dd>
          ) : (
            writes.map((s) => (
              <dd key={s.resource} className="flex justify-between text-sm py-0.5">
                <span>{s.resource}</span>
                <span className="text-ink2">{s.description}</span>
              </dd>
            ))
          )}
        </dl>
      )}

      <dt className="font-serif text-xs text-muted mt-4 mb-1 pb-1 border-b border-dotted border-line">Talks to</dt>
      {manifest.networkAllowlist.length === 0 ? (
        <dd className="text-sm py-0.5 text-muted">nothing outside the platform</dd>
      ) : (
        manifest.networkAllowlist.map((host) => (
          <dd key={host} className="flex justify-between text-sm py-0.5">
            <span>{host}</span>
            <span className="text-ink2">only</span>
          </dd>
        ))
      )}

      {manifest.approvalGates.length > 0 && (
        <>
          <dt className="font-serif text-xs text-muted mt-4 mb-1 pb-1 border-b border-dotted border-line">Asks first</dt>
          {manifest.approvalGates.map((g) => (
            <dd key={g.trigger} className="flex justify-between text-sm py-0.5">
              <span>{g.description}</span>
              <span className="text-ink2">always</span>
            </dd>
          ))}
        </>
      )}

      <div className="mt-5 pt-4 border-t border-line flex justify-between items-baseline">
        <span className="text-ink2 text-sm">Most it can spend per run</span>
        <span className="font-serif text-xl">{formatInr(manifest.costEnvelope.maxPerRun)}</span>
      </div>
    </aside>
  );
}
