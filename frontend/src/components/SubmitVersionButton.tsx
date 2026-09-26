"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { submitVersionAction } from "./submissionActions";

export function SubmitVersionButton({ agentId, versionId }: { agentId: string; versionId: string }) {
  const router = useRouter();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function submit() {
    setBusy(true);
    setError(null);
    const result = await submitVersionAction(agentId, versionId);
    setBusy(false);
    if (!result.ok) {
      setError(result.error);
      return;
    }
    router.refresh();
  }

  return (
    <div className="mt-6">
      <button
        type="button"
        disabled={busy}
        onClick={submit}
        className="bg-green text-white rounded-lg px-5 py-2 font-medium disabled:opacity-50"
      >
        {busy ? "Submitting…" : "Submit for review"}
      </button>
      {error && <p className="mt-2 text-red">{error}</p>}
    </div>
  );
}
