import { toFailure } from "./errors";

const e = (status: number, code: string | null, message: string, details: Record<string, unknown> = {}) => ({ status, code, message, details });

it.each([
  ["pan must match ^[A-Z]{5}[0-9]{4}[A-Z]$", "pan"],
  ["gstin must match ^[0-9]{2}[A-Z0-9]{13}$", "gstin"],
  ["phone must be a valid Indian mobile or E.164 number", "phone"],
  ["country must be a 2-letter code", "country"],
  ["legalName must not be blank", "legalName"],
  ["displayName must be at most 100 characters", "displayName"],
  ["city must not be blank", "city"],
  ["entityType must be one of [INDIVIDUAL]", "entityType"],
  ["agreementVersion must be 2.2", "agreementVersion"],
])("puts the INVALID_FIELD '%s' under %s in plain words", (message, field) => {
  const f = toFailure(e(400, "INVALID_FIELD", message));
  expect(f.field).toBe(field);
  expect(f.text).not.toMatch(/must match|\^|\[/);
});

it("falls back to the server message when the invalid field is unknown", () => {
  expect(toFailure(e(400, "INVALID_FIELD", "request body is malformed"))).toEqual({ text: "request body is malformed" });
});

it("names the missing steps and treats the PAN image as part of Tax IDs", () => {
  const f = toFailure(e(400, "VERIFICATION_INCOMPLETE", "m", { missingSteps: ["PHONE", "PAN_DOCUMENT"] }));
  expect(f.text).toBe("Finish these first: Phone, PAN card image.");
  expect(f.missingSteps).toEqual(["PHONE", "TAX_IDS"]);
});

it("gives one plain sentence for locked, too large and wrong type", () => {
  expect(toFailure(e(409, "VERIFICATION_LOCKED", "x")).text).toMatch(/reviewing/);
  expect(toFailure(e(413, "DOCUMENT_TOO_LARGE", "x")).text).toMatch(/5 MB/);
  expect(toFailure(e(413, null, "Request failed (413)")).text).toMatch(/5 MB/);
  expect(toFailure(e(415, "UNSUPPORTED_DOCUMENT_TYPE", "x")).text).toMatch(/JPG, PNG or PDF/);
  expect(toFailure(e(415, null, "Request failed (415)")).text).toMatch(/JPG, PNG or PDF/);
});

it("passes any other error message through", () => {
  expect(toFailure(e(500, null, "Request failed (500)")).text).toBe("Request failed (500)");
});
