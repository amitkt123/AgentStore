import { vi } from "vitest";

const patch = vi.hoisted(() => vi.fn());
const upload = vi.hoisted(() => vi.fn());
const submit = vi.hoisted(() => vi.fn());
vi.mock("@/lib/api/verification", () => ({ patchVerification: patch, uploadPanDocument: upload, submitVerification: submit }));
import { ApiError } from "@/lib/api/http";
import { saveStepAction, submitAction, uploadPanAction } from "./actions";

beforeEach(() => { patch.mockReset(); upload.mockReset(); submit.mockReset(); });

it("returns the saved view", async () => {
  patch.mockResolvedValue({ status: "DRAFT" });
  expect(await saveStepAction("PHONE", { phone: "9920012208" })).toEqual({ view: { status: "DRAFT" } });
  expect(patch).toHaveBeenCalledWith("PHONE", { phone: "9920012208" });
});

it("turns an INVALID_FIELD into a failure under that field", async () => {
  patch.mockRejectedValue(new ApiError(400, "INVALID_FIELD", "pan must match ^[A-Z]{5}[0-9]{4}[A-Z]$"));
  expect(await saveStepAction("TAX_IDS", { pan: "x" })).toEqual({ failure: { text: "Enter your PAN, like ABCDE1234F.", field: "pan" } });
});

it("turns a 413 on the upload into one sentence and an incomplete submit into the missing steps", async () => {
  upload.mockRejectedValue(new ApiError(413, null, "Request failed (413)"));
  expect(await uploadPanAction(new FormData())).toEqual({ failure: { text: "That file is over 5 MB. Choose a smaller one." } });
  submit.mockRejectedValue(new ApiError(400, "VERIFICATION_INCOMPLETE", "m", { missingSteps: ["PHONE"] }));
  expect(await submitAction()).toEqual({ failure: { text: "Finish these first: Phone.", missingSteps: ["PHONE"] } });
});

it("rethrows anything that is not an ApiError", async () => {
  patch.mockRejectedValue(new Error("boom"));
  await expect(saveStepAction("ABOUT", {})).rejects.toThrow("boom");
});
