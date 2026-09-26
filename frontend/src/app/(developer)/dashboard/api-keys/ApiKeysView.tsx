"use client";

import { useState } from "react";
import { StatusPill } from "@/components/StatusPill";
import type { ApiKeySummary } from "@/lib/types";
import { createApiKeyAction, revokeApiKeyAction } from "./actions";

const inputClass = "border border-line rounded-lg px-3 py-2";

function when(iso: string | null): string {
  return iso ? new Date(iso).toLocaleString() : "never";
}

export function ApiKeysView({ keys }: { keys: ApiKeySummary[] }) {
  const [name, setName] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  // The new key lives only in this state: it is not in the URL, in storage, or in the server-rendered page.
  const [newKey, setNewKey] = useState<string | null>(null);
  const [copied, setCopied] = useState(false);

  async function create(event: React.FormEvent) {
    event.preventDefault();
    setBusy(true);
    setError(null);
    const result = await createApiKeyAction(name.trim());
    setBusy(false);
    if (!result.ok) {
      setError(result.error);
      return;
    }
    setNewKey(result.key);
    setCopied(false);
    setName("");
  }

  async function revoke(id: string) {
    setError(null);
    const result = await revokeApiKeyAction(id);
    if (!result.ok) setError(result.error);
  }

  async function copy() {
    try {
      await navigator.clipboard.writeText(newKey ?? "");
      setCopied(true);
    } catch {
      setError("Could not copy automatically; select the key and copy it by hand");
    }
  }

  return (
    <div>
      <h1 className="font-serif text-2xl mb-2">API keys</h1>
      <p className="text-ink2 mb-6">
        Keys let the AgentStore CLI push code for your organisation. A key can create agents, versions and builds, and nothing else.
      </p>

      {newKey && (
        <section aria-label="New API key" className="mb-6 rounded-lg border border-line bg-paper2 p-4">
          <p className="font-medium mb-2">Copy your key now. You won't be able to see it again.</p>
          <code className="block break-all font-mono text-sm mb-3">{newKey}</code>
          <div className="flex gap-3">
            <button type="button" onClick={copy} className="bg-green text-white rounded-lg px-4 py-2 font-medium text-sm">
              {copied ? "Copied" : "Copy"}
            </button>
            <button type="button" onClick={() => setNewKey(null)} className="text-ink2 text-sm">
              Done
            </button>
          </div>
        </section>
      )}

      <form onSubmit={create} className="flex gap-3 mb-6">
        <input
          aria-label="Key name"
          placeholder="Key name, e.g. laptop"
          value={name}
          maxLength={100}
          onChange={(e) => setName(e.target.value)}
          className={`${inputClass} flex-1`}
        />
        <button
          type="submit"
          disabled={busy || name.trim() === ""}
          className="bg-green text-white rounded-lg px-4 py-2 font-medium text-sm disabled:opacity-50"
        >
          Create key
        </button>
      </form>
      {error && <p className="text-red mb-4">{error}</p>}

      {keys.length === 0 ? (
        <p className="text-muted py-8">You haven't created any API keys yet.</p>
      ) : (
        <div className="border-t border-line">
          {keys.map((key) => (
            <div key={key.id} className="grid grid-cols-[1.4fr_1fr_1fr_auto] gap-4 items-center py-3 border-b border-line">
              <div>
                <div className="font-semibold">{key.name}</div>
                <div className="font-mono text-sm text-ink2">{key.prefix}…</div>
              </div>
              <span className="text-sm text-ink2">Created {when(key.createdAt)}</span>
              <span className="text-sm text-ink2">Last used {when(key.lastUsedAt)}</span>
              {key.revokedAt ? (
                <StatusPill status="REVOKED" />
              ) : (
                <button type="button" aria-label={`Revoke ${key.name}`} onClick={() => revoke(key.id)} className="text-red text-sm">
                  Revoke
                </button>
              )}
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
