import type { DeveloperVerification, VerificationEntityType, VerificationStatus, VerificationStep } from "@/lib/api/types";
import type { Tone } from "@/ui/Pill";
import { FIELD_TEXT, type FieldName } from "./errors";

export const AGREEMENT_VERSION = "2.2";

export interface StepMeta { key: VerificationStep; name: string; title: string; sub: string }
export const STEPS: StepMeta[] = [
  { key: "ABOUT", name: "About you", title: "About you", sub: "This is who buyers see as the publisher of your agents." },
  { key: "TAX_IDS", name: "Tax IDs", title: "Tax IDs", sub: "Karya checks these against government records. We store them encrypted and show only the last few characters." },
  { key: "PHONE", name: "Phone", title: "Phone", sub: "A person at Karya confirms it. Buyers never see it." },
  { key: "AGREEMENT", name: "Developer agreement", title: "Developer agreement", sub: "Version 2.2. The main points in plain words:" },
];

export const stepIndex = (key: VerificationStep) => STEPS.findIndex((s) => s.key === key);
export const isLocked = (status: VerificationStatus) => status !== "DRAFT" && status !== "FIX_REQUESTED";

export function stepDone(v: DeveloperVerification, key: VerificationStep): boolean {
  switch (key) {
    case "ABOUT": return !!(v.legalName && v.displayName && v.country && v.city && v.entityType);
    case "TAX_IDS": return !!v.panMasked;
    case "PHONE": return !!v.phoneMasked;
    case "AGREEMENT": return !!v.agreementAcceptedAt;
  }
}

export function stepHint(v: DeveloperVerification, key: VerificationStep): string {
  switch (key) {
    case "ABOUT": {
      const kind = v.entityType === "INDIVIDUAL" ? "individual" : v.entityType ? "company" : null;
      return v.legalName ? [v.legalName, kind].filter(Boolean).join(", ") : "Who you are";
    }
    case "TAX_IDS": return "PAN, GSTIN if any";
    case "PHONE": return "Mobile number";
    case "AGREEMENT": return `Version ${AGREEMENT_VERSION}`;
  }
}

// A step is open once the backend has moved past it; a locked verification can be read end to end.
export const reachable = (v: DeveloperVerification, key: VerificationStep) => isLocked(v.status) || stepIndex(key) <= stepIndex(v.currentStep);

export function karyaHint(status: VerificationStatus): string {
  switch (status) {
    case "PENDING": return "Waiting, usually 2 working days";
    case "VERIFIED": return "Done";
    case "FIX_REQUESTED": return "Asked for a change";
    case "REJECTED": return "Not approved";
    default: return "After you send it";
  }
}

export function banner(v: DeveloperVerification): { tone: Tone; label: string; text: string } | null {
  switch (v.status) {
    case "PENDING": return { tone: "info", label: "In review", text: "Sent to Karya. Karya usually replies within 2 working days. Keep building meanwhile; your sandbox and test files work exactly as before." };
    case "VERIFIED": return { tone: "ok", label: "Verified", text: "You're verified. Buyers see your legal name, never your tax IDs or phone." };
    case "FIX_REQUESTED": return { tone: "warn", label: "Changes needed", text: v.decisionNote || "Karya asked for a change. Update what's needed and send it again." };
    case "REJECTED": return { tone: "bad", label: "Not approved", text: v.decisionNote || "Karya couldn't verify this account." };
    default: return null;
  }
}

// What the user has typed. The raw PAN, GSTIN and phone live here only until the step is saved.
export interface Draft {
  entityType: VerificationEntityType; legalName: string; displayName: string; country: string; city: string;
  pan: string; gstin: string; phone: string; editPan: boolean; editGstin: boolean; editPhone: boolean;
}

export const initialDraft = (v: DeveloperVerification): Draft => ({
  entityType: v.entityType ?? "INDIVIDUAL", legalName: v.legalName ?? "", displayName: v.displayName ?? "", country: v.country ?? "IN", city: v.city ?? "",
  pan: "", gstin: "", phone: "", editPan: false, editGstin: false, editPhone: false,
});

// The backend's patterns reject lowercase and spaces, so tidy what people type the way the mockup shows it.
export function fieldsFor(step: VerificationStep, d: Draft): Record<string, string> {
  switch (step) {
    case "ABOUT": return { entityType: d.entityType, legalName: d.legalName.trim(), displayName: d.displayName.trim(), country: d.country, city: d.city.trim() };
    case "TAX_IDS": {
      const out: Record<string, string> = {};
      if (d.pan.trim()) out.pan = d.pan.trim().toUpperCase();
      if (d.gstin.trim()) out.gstin = d.gstin.trim().toUpperCase();
      return out;
    }
    case "PHONE": return d.phone.trim() ? { phone: d.phone.replace(/[\s-]/g, "") } : {};
    case "AGREEMENT": return { agreementVersion: AGREEMENT_VERSION };
  }
}

export function validate(step: VerificationStep, d: Draft, v: DeveloperVerification, agreed: boolean): Partial<Record<FieldName, string>> {
  const out: Partial<Record<FieldName, string>> = {};
  const need = (f: FieldName, value: string) => { if (!value.trim()) out[f] = FIELD_TEXT[f]; };
  switch (step) {
    case "ABOUT": need("legalName", d.legalName); need("displayName", d.displayName); need("city", d.city); break;
    case "TAX_IDS": if (!d.pan.trim() && !v.panMasked) out.pan = FIELD_TEXT.pan; break;
    case "PHONE": if (!d.phone.trim() && !v.phoneMasked) out.phone = FIELD_TEXT.phone; break;
    case "AGREEMENT": if (!agreed) out.agreementVersion = FIELD_TEXT.agreementVersion; break;
  }
  return out;
}

export function missingForSubmit(v: DeveloperVerification, agreed: boolean): string[] {
  const out: string[] = [];
  if (!stepDone(v, "ABOUT")) out.push("About you");
  if (!v.panMasked) out.push("Tax IDs");
  if (!v.panDocumentUploaded) out.push("PAN card image");
  if (!v.phoneMasked) out.push("Phone");
  if (!agreed) out.push("Developer agreement");
  return out;
}
