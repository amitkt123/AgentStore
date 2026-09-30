import type { DeveloperVerification, VerificationStep } from "@/lib/api/types";

export type FieldName = "legalName" | "displayName" | "country" | "city" | "entityType" | "pan" | "gstin" | "phone" | "agreementVersion";

export const FIELD_TEXT: Record<FieldName, string> = {
  legalName: "Enter your legal name, as on your PAN.",
  displayName: "Enter the name buyers should see on your listings.",
  country: "Choose your country.",
  city: "Enter your city.",
  entityType: "Choose how you're registered.",
  pan: "Enter your PAN, like ABCDE1234F.",
  gstin: "A GSTIN has 15 characters, like 22ABCDE1234F1Z5.",
  phone: "Enter a 10-digit Indian mobile number, or a number that starts with +.",
  agreementVersion: "Accept the agreement to continue.",
};

export interface Failure { text: string; field?: FieldName; missingSteps?: VerificationStep[] }
export type StepResult = { view: DeveloperVerification } | { failure: Failure };

const STEP_NAME: Record<string, string> = { ABOUT: "About you", TAX_IDS: "Tax IDs", PHONE: "Phone", AGREEMENT: "Developer agreement", PAN_DOCUMENT: "PAN card image" };
const FIELD_RE = /^(legalName|displayName|country|city|entityType|pan|gstin|phone|agreementVersion)\b/;
const TOO_LARGE = "That file is over 5 MB. Choose a smaller one.";
const WRONG_TYPE = "Use a JPG, PNG or PDF.";

// The backend's INVALID_FIELD carries no field name, only a message that starts with it.
export function toFailure(e: { status: number; code: string | null; message: string; details?: Record<string, unknown> }): Failure {
  if (e.status === 413 || e.code === "DOCUMENT_TOO_LARGE") return { text: TOO_LARGE };
  if (e.status === 415 || e.code === "UNSUPPORTED_DOCUMENT_TYPE") return { text: WRONG_TYPE };
  if (e.code === "VERIFICATION_LOCKED") return { text: "Karya is reviewing this, so it can't be changed right now." };
  if (e.code === "VERIFICATION_INCOMPLETE") {
    const raw = e.details?.missingSteps;
    const missing = Array.isArray(raw) ? (raw as string[]) : [];
    const steps = Array.from(new Set(missing.map((m) => (m === "PAN_DOCUMENT" ? "TAX_IDS" : m)))) as VerificationStep[];
    return { text: `Finish these first: ${missing.map((m) => STEP_NAME[m] ?? m).join(", ")}.`, missingSteps: steps };
  }
  if (e.code === "INVALID_FIELD") {
    const field = FIELD_RE.exec(e.message)?.[1] as FieldName | undefined;
    if (field) return { text: FIELD_TEXT[field], field };
  }
  return { text: e.message };
}

export const TOO_LARGE_TEXT = TOO_LARGE;
