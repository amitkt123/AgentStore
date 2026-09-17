"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { WizardRail } from "./WizardRail";
import { PermissionStatementDoc } from "./PermissionStatementDoc";
import { toParsedManifest, emptyManifestDraft } from "@/lib/manifestDraft";
import type { ApprovalGateDecl, ManifestDraft, Scope } from "@/lib/types";
import { createAgentAction, createVersionAction, submitVersionAction } from "./submissionActions";

interface ExistingAgent {
  id: string;
  name: string;
  category: string;
}

const STEPS = ["Basics", "Scopes", "Cost & limits", "Review & submit"];
const inputClass = "border border-line rounded-lg px-3 py-2 w-full mb-3";

export function SubmissionWizard({ existingAgent }: { existingAgent?: ExistingAgent }) {
  const [stepIndex, setStepIndex] = useState(0);
  const [agentName, setAgentName] = useState(existingAgent?.name ?? "");
  const [category, setCategory] = useState(existingAgent?.category ?? "");
  const [description, setDescription] = useState("");
  const [versionNumber, setVersionNumber] = useState("1.0.0");
  const [imageRef, setImageRef] = useState("");
  const [changelog, setChangelog] = useState("");
  const [draft, setDraft] = useState<ManifestDraft>(() => emptyManifestDraft(existingAgent?.name ?? ""));
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [createdAgentId, setCreatedAgentId] = useState<string | null>(null);
  const router = useRouter();

  function addScope() {
    setDraft((d) => ({ ...d, scopes: [...d.scopes, { type: "READ", resource: "", description: "" }] }));
  }
  function updateScope(i: number, patch: Partial<Scope>) {
    setDraft((d) => ({ ...d, scopes: d.scopes.map((s, idx) => (idx === i ? { ...s, ...patch } : s)) }));
  }
  function removeScope(i: number) {
    setDraft((d) => ({ ...d, scopes: d.scopes.filter((_, idx) => idx !== i) }));
  }
  function addGate() {
    setDraft((d) => ({ ...d, approvalGates: [...d.approvalGates, { trigger: "", description: "" }] }));
  }
  function updateGate(i: number, patch: Partial<ApprovalGateDecl>) {
    setDraft((d) => ({ ...d, approvalGates: d.approvalGates.map((g, idx) => (idx === i ? { ...g, ...patch } : g)) }));
  }
  function removeGate(i: number) {
    setDraft((d) => ({ ...d, approvalGates: d.approvalGates.filter((_, idx) => idx !== i) }));
  }

  async function handleSubmit() {
    setSubmitting(true);
    setError(null);
    const finalDraft: ManifestDraft = { ...draft, name: agentName, version: versionNumber };

    let agentId = existingAgent?.id ?? createdAgentId ?? undefined;
    if (!agentId) {
      const agentResult = await createAgentAction({ name: agentName, category, description });
      if (!agentResult.ok) {
        setSubmitting(false);
        setError(agentResult.error);
        return;
      }
      agentId = agentResult.agentId;
      setCreatedAgentId(agentResult.agentId);
    }

    const versionResult = await createVersionAction(agentId, {
      versionNumber,
      manifestJson: JSON.stringify(finalDraft),
      imageRef,
      changelog,
    });
    if (!versionResult.ok) {
      setSubmitting(false);
      setError(versionResult.error);
      return;
    }

    const submitResult = await submitVersionAction(agentId, versionResult.versionId);
    setSubmitting(false);
    if (!submitResult.ok) {
      setError(submitResult.error);
      return;
    }

    router.push(`/dashboard/agents/${agentId}/versions/${versionResult.versionId}/status`);
  }

  return (
    <div>
      <WizardRail steps={STEPS} currentIndex={stepIndex} />

      {stepIndex === 0 && (
        <div>
          {!existingAgent && (
            <>
              <input placeholder="Agent name" value={agentName} onChange={(e) => setAgentName(e.target.value)} className={inputClass} />
              <input placeholder="Category" value={category} onChange={(e) => setCategory(e.target.value)} className={inputClass} />
              <textarea placeholder="One-line job description" value={description} onChange={(e) => setDescription(e.target.value)} className={inputClass} />
            </>
          )}
          <input placeholder="Version number, e.g. 1.0.0" value={versionNumber} onChange={(e) => setVersionNumber(e.target.value)} className={inputClass} />
          <input placeholder="E2B template ID" value={imageRef} onChange={(e) => setImageRef(e.target.value)} className={inputClass} />
          <textarea placeholder="What changed in this version" value={changelog} onChange={(e) => setChangelog(e.target.value)} className={inputClass} />
        </div>
      )}

      {stepIndex === 1 && (
        <div>
          <h2 className="font-semibold mb-2">Declare every scope it needs</h2>
          {draft.scopes.map((scope, i) => (
            <div key={i} className="grid grid-cols-[1fr_100px_1fr_auto] gap-2 mb-2">
              <input placeholder="resource, e.g. bank_statement" value={scope.resource} onChange={(e) => updateScope(i, { resource: e.target.value })} className="border border-line rounded px-2 py-1" />
              <select value={scope.type} onChange={(e) => updateScope(i, { type: e.target.value as Scope["type"] })} className="border border-line rounded px-2 py-1">
                <option value="READ">Read</option>
                <option value="WRITE">Write</option>
              </select>
              <input placeholder="description" value={scope.description} onChange={(e) => updateScope(i, { description: e.target.value })} className="border border-line rounded px-2 py-1" />
              <button type="button" onClick={() => removeScope(i)} className="text-red text-sm">Remove</button>
            </div>
          ))}
          <button type="button" onClick={addScope} className="border border-dashed border-line rounded px-3 py-2 text-sm w-full text-ink2 mb-4">
            + Add a scope
          </button>

          <label className="block text-sm font-medium mb-1">Network destinations (comma-separated)</label>
          <input
            value={draft.network.allowlist.join(", ")}
            onChange={(e) => setDraft((d) => ({ ...d, network: { allowlist: e.target.value.split(",").map((s) => s.trim()).filter(Boolean) } }))}
            className={inputClass}
          />

          <h2 className="font-semibold mb-2">Approval gates</h2>
          {draft.approvalGates.map((gate, i) => (
            <div key={i} className="grid grid-cols-[1fr_1fr_auto] gap-2 mb-2">
              <input placeholder="trigger, e.g. MESSAGE_CUSTOMER" value={gate.trigger} onChange={(e) => updateGate(i, { trigger: e.target.value })} className="border border-line rounded px-2 py-1" />
              <input placeholder="description" value={gate.description} onChange={(e) => updateGate(i, { description: e.target.value })} className="border border-line rounded px-2 py-1" />
              <button type="button" onClick={() => removeGate(i)} className="text-red text-sm">Remove</button>
            </div>
          ))}
          <button type="button" onClick={addGate} className="border border-dashed border-line rounded px-3 py-2 text-sm w-full text-ink2">
            + Add an approval gate
          </button>
        </div>
      )}

      {stepIndex === 2 && (
        <div>
          <h2 className="font-semibold mb-2">What can it cost a company</h2>
          <div className="grid grid-cols-2 gap-3 mb-3">
            <input
              placeholder="Max per run (INR)"
              type="number"
              value={draft.costEnvelope.maxPerRun}
              onChange={(e) => setDraft((d) => ({ ...d, costEnvelope: { ...d.costEnvelope, maxPerRun: Number(e.target.value) } }))}
              className="border border-line rounded-lg px-3 py-2"
            />
            <input
              placeholder="Unit price (INR)"
              type="number"
              value={draft.costEnvelope.unitPrice}
              onChange={(e) => setDraft((d) => ({ ...d, costEnvelope: { ...d.costEnvelope, unitPrice: Number(e.target.value) } }))}
              className="border border-line rounded-lg px-3 py-2"
            />
          </div>
          <div className="grid grid-cols-2 gap-3">
            <select
              value={draft.costEnvelope.pricingUnit}
              onChange={(e) => setDraft((d) => ({ ...d, costEnvelope: { ...d.costEnvelope, pricingUnit: e.target.value as typeof d.costEnvelope.pricingUnit } }))}
              className="border border-line rounded-lg px-3 py-2"
            >
              <option value="OUTCOME">Per outcome</option>
              <option value="METERED">Metered</option>
              <option value="SUBSCRIPTION">Subscription</option>
            </select>
            <input
              placeholder="Unit label, e.g. matched_invoice"
              value={draft.costEnvelope.unitLabel}
              onChange={(e) => setDraft((d) => ({ ...d, costEnvelope: { ...d.costEnvelope, unitLabel: e.target.value } }))}
              className="border border-line rounded-lg px-3 py-2"
            />
          </div>
        </div>
      )}

      {stepIndex === 3 && (
        <div>
          <h2 className="font-semibold mb-4">Ready to submit</h2>
          <p className="text-ink2 mb-4">This is exactly what companies will see. Submitting starts automated review.</p>
          <PermissionStatementDoc manifest={toParsedManifest({ ...draft, name: agentName, version: versionNumber })} mode="preview" />
          {error && <p className="text-red mt-4">{error}</p>}
        </div>
      )}

      <div className="flex justify-between mt-6">
        <button type="button" disabled={stepIndex === 0} onClick={() => setStepIndex((i) => i - 1)} className="text-ink2 disabled:opacity-0">
          Back
        </button>
        {stepIndex < STEPS.length - 1 ? (
          <button type="button" onClick={() => setStepIndex((i) => i + 1)} className="bg-green text-white rounded-lg px-5 py-2 font-medium">
            Continue
          </button>
        ) : (
          <button type="button" disabled={submitting} onClick={handleSubmit} className="bg-green text-white rounded-lg px-5 py-2 font-medium disabled:opacity-50">
            {submitting ? "Submitting…" : "Submit for review"}
          </button>
        )}
      </div>
    </div>
  );
}
