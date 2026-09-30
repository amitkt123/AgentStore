import type { DeveloperVerification } from "@/lib/api/types";

export const view = (over: Partial<DeveloperVerification> = {}): DeveloperVerification => ({
  status: "DRAFT", currentStep: "ABOUT", entityType: null, legalName: null, displayName: null, country: null, city: null,
  panMasked: null, gstinMasked: null, panDocumentUploaded: false, phoneMasked: null, agreementVersion: null,
  agreementAcceptedAt: null, resubmissionCount: 0, submittedAt: null, ...over,
});
