import { cell, decisionText } from "./previewCells";
import type { GateDetail } from "@/lib/api/types";

describe("cell", () => {
  it("groups numbers the Indian way and marks them numeric", () => expect(cell(185000)).toEqual({ text: "1,85,000", numeric: true }));
  it("treats numeric strings as numbers", () => expect(cell("18450.75")).toEqual({ text: "18,450.75", numeric: true }));
  it("keeps at most two decimals", () => expect(cell(1285.456)).toEqual({ text: "1,285.46", numeric: true }));
  it("renders null and undefined as blank", () => {
    expect(cell(null)).toEqual({ text: "", numeric: false });
    expect(cell(undefined)).toEqual({ text: "", numeric: false });
  });
  it("leaves text and dates alone", () => {
    expect(cell("5000 Salaries")).toEqual({ text: "5000 Salaries", numeric: false });
    expect(cell("2026-09-01")).toEqual({ text: "2026-09-01", numeric: false });
  });
});

describe("decisionText", () => {
  const now = new Date();
  const at = new Date(now.getFullYear(), now.getMonth(), now.getDate(), 14, 17).toISOString();
  const g = (over: Partial<GateDetail>): GateDetail => ({
    gateId: "g", action: "a", summary: "s", payload: {}, status: "APPROVED", expiresAt: at, estimatedUnits: null, costIfApproved: null,
    preview: null, decidedVia: "WEB", decidedByUserId: "u1", decidedAt: at, note: null, ...over,
  });
  it("speaks to the viewer who decided", () => {
    expect(decisionText(g({}), "u1")).toBe("You approved this at 14:17.");
    expect(decisionText(g({ status: "REJECTED" }), "u1")).toBe("You rejected this at 14:17.");
  });
  it("does not claim the viewer decided when someone else did", () => {
    expect(decisionText(g({}), "u2")).toBe("Approved at 14:17.");
    expect(decisionText(g({ status: "REJECTED", decidedVia: "EMAIL_LINK" }), "u2")).toBe("Rejected at 14:17 by email link.");
  });
});
