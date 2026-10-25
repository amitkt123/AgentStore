"use client";
import styles from "./verification.module.css";
import { Dropzone } from "@/ui/Dropzone";
import { Field } from "@/ui/Field";
import { Icon } from "@/ui/Icon";
import { KeyValue } from "@/ui/KeyValue";
import { Segmented } from "@/ui/Segmented";
import type { DeveloperVerification, VerificationEntityType } from "@/lib/api/types";
import type { FieldName } from "./errors";
import type { Draft } from "./model";

interface FormProps {
  view: DeveloperVerification;
  draft: Draft;
  set: (patch: Partial<Draft>) => void;
  errors: Partial<Record<FieldName, string>>;
  disabled: boolean;
}

const COMPANY_TYPES: { value: VerificationEntityType; label: string }[] = [
  { value: "PRIVATE_LIMITED", label: "Private limited company" },
  { value: "LLP", label: "LLP" },
  { value: "PARTNERSHIP", label: "Partnership" },
  { value: "OTHER", label: "Other" },
];

export function AboutForm({ draft, set, errors, disabled }: FormProps) {
  const company = draft.entityType !== "INDIVIDUAL";
  return (
    <>
      <div className={styles.fieldBox}>
        <span className={styles.lab}>You are</span>
        <Segmented
          label="Entity type"
          value={company ? "company" : "individual"}
          disabled={disabled}
          options={[{ value: "individual", label: "An individual" }, { value: "company", label: "A company" }]}
          onChange={(v) => set({ entityType: v === "individual" ? "INDIVIDUAL" : company ? draft.entityType : "PRIVATE_LIMITED" })}
        />
      </div>
      {company && (
        <div className={styles.fieldBox}>
          <label htmlFor="company-type">Type of company</label>
          <select id="company-type" className={styles.select} value={draft.entityType} disabled={disabled} onChange={(e) => set({ entityType: e.target.value as VerificationEntityType })}>
            {COMPANY_TYPES.map((t) => <option key={t.value} value={t.value}>{t.label}</option>)}
          </select>
        </div>
      )}
      <Field label="Legal name, as on your PAN" value={draft.legalName} error={errors.legalName} disabled={disabled} maxLength={200} onChange={(e) => set({ legalName: e.target.value })} />
      <div className={styles.fieldBox}>
        <label htmlFor="country">Country</label>
        <select id="country" className={styles.select} value={draft.country} disabled={disabled} onChange={(e) => set({ country: e.target.value })}>
          <option value="IN">India</option>
        </select>
        {errors.country && <span className={styles.err}>{errors.country}</span>}
      </div>
      <Field label="Name shown on listings" value={draft.displayName} error={errors.displayName} disabled={disabled} maxLength={100} onChange={(e) => set({ displayName: e.target.value })} />
      <Field label="City" value={draft.city} error={errors.city} disabled={disabled} maxLength={100} onChange={(e) => set({ city: e.target.value })} />
    </>
  );
}

// Shows a saved ID as its masked last characters with a Replace button; the raw value is never available to show.
function Saved({ label, masked, editing, onEdit, disabled, children }: { label: string; masked: string | null; editing: boolean; onEdit: () => void; disabled: boolean; children: React.ReactNode }) {
  if (masked && !editing) {
    return (
      <KeyValue label={label}>
        <span className="mono">•••• {masked}</span>{" "}
        <button type="button" className={styles.link} disabled={disabled} aria-label={`Replace ${label}`} onClick={onEdit}>Replace</button>
      </KeyValue>
    );
  }
  return <>{children}</>;
}

export function TaxForm({ view, draft, set, errors, disabled, onPanFile }: FormProps & { onPanFile: (f: File) => void }) {
  return (
    <>
      <Saved label="PAN" masked={view.panMasked} editing={draft.editPan} disabled={disabled} onEdit={() => set({ editPan: true })}>
        <Field label="PAN" className="mono" value={draft.pan} error={errors.pan} disabled={disabled} maxLength={10} autoComplete="off" onChange={(e) => set({ pan: e.target.value })} />
      </Saved>
      <Saved label="GSTIN" masked={view.gstinMasked} editing={draft.editGstin} disabled={disabled} onEdit={() => set({ editGstin: true })}>
        <Field label="GSTIN, if you have one" className="mono" value={draft.gstin} error={errors.gstin} disabled={disabled} maxLength={15} autoComplete="off" placeholder="Not needed for individuals under the threshold" onChange={(e) => set({ gstin: e.target.value })} />
      </Saved>
      {view.panDocumentUploaded && <KeyValue label="PAN card image">Uploaded</KeyValue>}
      <Dropzone label={view.panDocumentUploaded ? "Replace PAN card image" : "PAN card image"} disabled={disabled} onFile={onPanFile} />
      <p className={styles.sub}>A clear photo or scan, JPG, PNG or PDF, up to 5 MB.</p>
    </>
  );
}

export function PhoneForm({ view, draft, set, errors, disabled }: FormProps) {
  return (
    <Saved label="Mobile number" masked={view.phoneMasked} editing={draft.editPhone} disabled={disabled} onEdit={() => set({ editPhone: true })}>
      <Field label="Mobile number" type="tel" autoComplete="tel" value={draft.phone} error={errors.phone} disabled={disabled} onChange={(e) => set({ phone: e.target.value })} />
    </Saved>
  );
}

const POINTS = [
  "Your agents only touch what their manifest declares, and buyers see exactly that.",
  "Karya keeps 20% of what buyers pay. AI usage through Karya is charged at cost.",
  "Buyer data is never yours to keep, copy or train on.",
  "Karya can pause a version that misbehaves, and decides buyer disputes.",
  "You are paid for services Karya resells. Your earnings balance is money owed for services, not a deposit.",
  "Withdrawals go only to a verified account in your own name. Karya may freeze them while it investigates fraud or a dispute, and tax may be withheld where the law requires it.",
];

export function AgreementForm({ agreed, onAgreed, error, disabled }: { agreed: boolean; onAgreed: (v: boolean) => void; error?: string; disabled: boolean }) {
  return (
    <>
      <div>
        {POINTS.map((p) => (
          <div key={p} className={styles.bul}><Icon name="check" /><span>{p}</span></div>
        ))}
      </div>
      <label className={styles.chk}>
        <input type="checkbox" checked={agreed} disabled={disabled} onChange={(e) => onAgreed(e.target.checked)} />
        I've read the full <a href="/legal/developer-agreement" target="_blank" rel="noreferrer">Developer Distribution Agreement 2.2</a> and accept it
      </label>
      {error && <span className={styles.err}>{error}</span>}
    </>
  );
}
