import { stepNote, stepText } from "./stepText";

it("uses plain words", () => {
  expect(stepText({ kind: "GATE_REQUESTED", detail: "{}", createdAt: "" })).toBe("Asked for your approval");
  expect(stepText({ kind: "NETWORK_BLOCKED", detail: "{}", createdAt: "" })).toBe("A website request was blocked");
});
it("falls back for an unknown kind", () => {
  expect(stepText({ kind: "SOMETHING_NEW" as never, detail: "", createdAt: "" })).toBe("something new");
});

describe("stepNote", () => {
  const log = (detail: string) => ({ kind: "AGENT_LOG" as const, detail, createdAt: "" });
  it("reads the message an agent logged", () => {
    expect(stepNote(log('{"level":"INFO","fields":{"lines":10},"message":"Matched the lines to accounts"}'))).toBe("Matched the lines to accounts");
  });
  it("uses plain text when the detail is not JSON", () => expect(stepNote(log("Matched 10 lines"))).toBe("Matched 10 lines"));
  it("has no note for an empty detail or an empty message", () => {
    expect(stepNote(log(""))).toBeNull();
    expect(stepNote(log('{"message":""}'))).toBeNull();
  });
  it("cuts a long note to 200 characters", () => {
    const note = stepNote(log("x".repeat(300)))!;
    expect(note).toHaveLength(201);
    expect(note.endsWith("…")).toBe(true);
  });
  it("has no note for platform steps", () => expect(stepNote({ kind: "COMPLETE", detail: '{"message":"x"}', createdAt: "" })).toBeNull());
});
