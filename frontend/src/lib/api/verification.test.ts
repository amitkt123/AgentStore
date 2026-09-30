import { vi } from "vitest";
vi.mock("next/headers", () => ({ cookies: () => ({ get: () => ({ value: "tok" }) }) }));
vi.mock("next/navigation", () => ({ redirect: vi.fn() }));
import { getVerification, patchVerification, submitVerification, uploadPanDocument } from "./verification";

const ok = (body: unknown) => vi.fn().mockResolvedValue(new Response(JSON.stringify(body), { status: 200 }));
beforeEach(() => { process.env.BACKEND_URL = "http://be"; });

it("reads the verification state", async () => {
  const f = ok({ status: "DRAFT" });
  vi.stubGlobal("fetch", f);
  expect(await getVerification()).toEqual({ status: "DRAFT" });
  expect(f.mock.calls[0][0]).toBe("http://be/api/v1/developer/verification");
});

it("patches one step with its fields", async () => {
  const f = ok({});
  vi.stubGlobal("fetch", f);
  await patchVerification("TAX_IDS", { pan: "ABCDE1234F" });
  const init = f.mock.calls[0][1];
  expect(f.mock.calls[0][0]).toBe("http://be/api/v1/developer/verification");
  expect(init.method).toBe("PATCH");
  expect(JSON.parse(init.body)).toEqual({ step: "TAX_IDS", fields: { pan: "ABCDE1234F" } });
});

it("uploads the PAN document as multipart without forcing a content type", async () => {
  const f = ok({});
  vi.stubGlobal("fetch", f);
  const fd = new FormData();
  fd.set("file", new Blob(["x"]), "pan.pdf");
  await uploadPanDocument(fd);
  expect(f.mock.calls[0][0]).toBe("http://be/api/v1/developer/verification/pan-document");
  expect(f.mock.calls[0][1].method).toBe("POST");
  expect(f.mock.calls[0][1].body).toBe(fd);
  expect(f.mock.calls[0][1].headers["Content-Type"]).toBeUndefined();
});

it("submits, and surfaces the missing steps on an incomplete submit", async () => {
  const f = ok({});
  vi.stubGlobal("fetch", f);
  await submitVerification();
  expect(f.mock.calls[0][0]).toBe("http://be/api/v1/developer/verification/submit");
  expect(f.mock.calls[0][1].method).toBe("POST");

  vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(JSON.stringify({ code: "VERIFICATION_INCOMPLETE", message: "m", missingSteps: ["PHONE"] }), { status: 400 })));
  await expect(submitVerification()).rejects.toMatchObject({ status: 400, code: "VERIFICATION_INCOMPLETE", details: { missingSteps: ["PHONE"] } });
});
