import { banner, fieldsFor, initialDraft, isLocked, karyaHint, missingForSubmit, reachable, stepDone, validate } from "./model";
import { view } from "./fixtures";

const complete = view({
  currentStep: "AGREEMENT", entityType: "INDIVIDUAL", legalName: "Asha Kulkarni", displayName: "Asha Kulkarni", country: "IN", city: "Pune",
  panMasked: "234L", panDocumentUploaded: true, phoneMasked: "2208",
});

it("normalises what is typed before it is sent", () => {
  const d = { ...initialDraft(view()), pan: " abcde1234f ", gstin: "22abcde1234f1z5", phone: "+91 99200-12208", legalName: " Asha ", displayName: "Asha", city: " Pune " };
  expect(fieldsFor("TAX_IDS", d)).toEqual({ pan: "ABCDE1234F", gstin: "22ABCDE1234F1Z5" });
  expect(fieldsFor("PHONE", d)).toEqual({ phone: "+919920012208" });
  expect(fieldsFor("ABOUT", d)).toEqual({ entityType: "INDIVIDUAL", legalName: "Asha", displayName: "Asha", country: "IN", city: "Pune" });
  expect(fieldsFor("AGREEMENT", d)).toEqual({ agreementVersion: "2.2" });
});

it("sends nothing for a tax or phone step the user left alone", () => {
  expect(fieldsFor("TAX_IDS", initialDraft(complete))).toEqual({});
  expect(fieldsFor("PHONE", initialDraft(complete))).toEqual({});
});

it("asks for what is missing, and not for what is already saved", () => {
  const blank = initialDraft(view());
  expect(Object.keys(validate("ABOUT", blank, view(), false)).sort()).toEqual(["city", "displayName", "legalName"]);
  expect(validate("TAX_IDS", blank, view(), false).pan).toBeDefined();
  expect(validate("TAX_IDS", blank, complete, false)).toEqual({});
  expect(validate("PHONE", blank, view(), false).phone).toBeDefined();
  expect(validate("PHONE", blank, complete, false)).toEqual({});
  expect(validate("AGREEMENT", blank, complete, false).agreementVersion).toBeDefined();
  expect(validate("AGREEMENT", blank, complete, true)).toEqual({});
});

it("lists what still blocks the submit, using the step names", () => {
  expect(missingForSubmit(view(), false)).toEqual(["About you", "Tax IDs", "PAN card image", "Phone", "Developer agreement"]);
  expect(missingForSubmit(complete, false)).toEqual(["Developer agreement"]);
  expect(missingForSubmit(complete, true)).toEqual([]);
});

it("derives step state from the saved view and gates the steps the user has not reached", () => {
  expect(stepDone(complete, "ABOUT")).toBe(true);
  expect(stepDone(view(), "TAX_IDS")).toBe(false);
  expect(reachable(view({ currentStep: "TAX_IDS" }), "TAX_IDS")).toBe(true);
  expect(reachable(view({ currentStep: "TAX_IDS" }), "PHONE")).toBe(false);
  expect(reachable(view({ status: "PENDING" }), "AGREEMENT")).toBe(true);
});

it("locks everything except draft and fix-requested", () => {
  expect(isLocked("DRAFT")).toBe(false);
  expect(isLocked("FIX_REQUESTED")).toBe(false);
  expect(["PENDING", "VERIFIED", "REJECTED"].every((s) => isLocked(s as never))).toBe(true);
});

it("never prints undefined when the reviewer's note is missing", () => {
  const note = banner(view({ status: "FIX_REQUESTED" }));
  expect(note?.text).toMatch(/Karya asked for a change/);
  expect(note?.text).not.toMatch(/undefined|null/);
  expect(banner(view({ status: "FIX_REQUESTED", decisionNote: "PAN image is blurry" }))?.text).toBe("PAN image is blurry");
  expect(banner(view({ status: "REJECTED" }))?.tone).toBe("bad");
  expect(banner(view({ status: "DRAFT" }))).toBeNull();
});

it("says what Karya is doing for each status", () => {
  expect(karyaHint("DRAFT")).toBe("After you send it");
  expect(karyaHint("PENDING")).toMatch(/2 working days/);
});
