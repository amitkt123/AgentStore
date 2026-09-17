"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { PermissionStatementDoc } from "@/components/PermissionStatementDoc";
import { formatInr } from "@/lib/format";
import type { Manifest } from "@/lib/types";
import { createConnectionAction } from "./actions";

type Step = "grant" | "cap" | "confirm";

export function ConnectWizard({ agentId, manifest }: { agentId: string; manifest: Manifest }) {
  const [step, setStep] = useState<Step>("grant");
  const [grantedResources, setGrantedResources] = useState<string[]>(manifest.scopes.map((s) => s.resource));
  const [spendCap, setSpendCap] = useState(String(manifest.costEnvelope.maxPerRun));
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const router = useRouter();

  const spendCapExceedsMax = Number(spendCap) > manifest.costEnvelope.maxPerRun;

  async function handleConfirm() {
    setSubmitting(true);
    setError(null);
    const result = await createConnectionAction({ agentId, grantedScopes: grantedResources, spendCap: Number(spendCap) });
    setSubmitting(false);
    if (!result.ok) {
      setError(result.error);
      return;
    }
    router.push(`/runs?connected=${result.connectionId}`);
  }

  return (
    <div className="max-w-2xl mx-auto">
      {step === "grant" && (
        <>
          <h1 className="font-serif text-xl mb-2">Grant exactly what the agent declared</h1>
          <p className="text-ink2 mb-4">You can turn off anything below; you can never grant more than it declared.</p>
          <PermissionStatementDoc manifest={manifest} mode="grant" onGrantChange={setGrantedResources} />
          <button className="mt-6 bg-green text-white rounded-lg px-5 py-2 font-medium" onClick={() => setStep("cap")}>
            Continue
          </button>
        </>
      )}
      {step === "cap" && (
        <>
          <h1 className="font-serif text-xl mb-2">Set a per-run spend limit</h1>
          <p className="text-ink2 mb-4">
            The agent's own declared per-run cap is {formatInr(manifest.costEnvelope.maxPerRun)} — a reasonable ceiling to start from.
          </p>
          <input
            type="number"
            value={spendCap}
            onChange={(e) => setSpendCap(e.target.value)}
            className="border border-line rounded-lg px-3 py-2 w-full"
            min={0}
          />
          {spendCapExceedsMax && (
            <p className="text-red mt-2 text-sm">
              Cannot exceed the agent's declared per-run cap of {formatInr(manifest.costEnvelope.maxPerRun)}.
            </p>
          )}
          <div className="flex justify-between mt-6">
            <button onClick={() => setStep("grant")} className="text-ink2">Back</button>
            <button
              disabled={spendCapExceedsMax}
              className="bg-green text-white rounded-lg px-5 py-2 font-medium disabled:opacity-50"
              onClick={() => setStep("confirm")}
            >
              Continue
            </button>
          </div>
        </>
      )}
      {step === "confirm" && (
        <>
          <h1 className="font-serif text-xl mb-2">Confirm connection</h1>
          <ul className="mb-4 text-sm text-ink2 list-disc pl-5">
            <li>Granted: {grantedResources.join(", ") || "nothing"}</li>
            <li>Per-run spend limit: {formatInr(Number(spendCap))}</li>
          </ul>
          {error && <p className="text-red mb-4">{error}</p>}
          <div className="flex justify-between mt-6">
            <button onClick={() => setStep("cap")} className="text-ink2">Back</button>
            <button
              disabled={submitting}
              className="bg-green text-white rounded-lg px-5 py-2 font-medium disabled:opacity-50"
              onClick={handleConfirm}
            >
              {submitting ? "Connecting…" : "Confirm"}
            </button>
          </div>
        </>
      )}
    </div>
  );
}
