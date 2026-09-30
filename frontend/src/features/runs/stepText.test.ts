import { stepText } from "./stepText";

it("uses plain words", () => {
  expect(stepText({ kind: "GATE_REQUESTED", detail: "{}", createdAt: "" })).toBe("Asked for your approval");
  expect(stepText({ kind: "NETWORK_BLOCKED", detail: "{}", createdAt: "" })).toBe("A website request was blocked");
});
it("falls back for an unknown kind", () => {
  expect(stepText({ kind: "SOMETHING_NEW" as never, detail: "", createdAt: "" })).toBe("something new");
});
