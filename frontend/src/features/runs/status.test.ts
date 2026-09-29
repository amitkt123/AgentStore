import { isTerminal, statusPill, terminalText } from "./status";

it("uses plain words", () => {
  expect(statusPill("AWAITING_APPROVAL")).toEqual({ tone: "warn", text: "Needs you" });
  expect(statusPill("COMPLETED")).toEqual({ tone: "ok", text: "Finished" });
  expect(statusPill("FAILED").tone).toBe("bad");
});
it("knows terminal states", () => {
  ["COMPLETED", "FAILED", "CANCELLED", "TIMED_OUT", "EXPIRED"].forEach((s) => expect(isTerminal(s as never)).toBe(true));
  ["PENDING", "PENDING_BUILD", "RUNNING", "AWAITING_APPROVAL"].forEach((s) => expect(isTerminal(s as never)).toBe(false));
});
it("explains why a run stopped", () => {
  expect(terminalText("GATE_REJECTED")).toMatch(/rejected/i);
  expect(terminalText("COST_CAP_REACHED")).toMatch(/limit/i);
  expect(terminalText(null)).toBeNull();
});
