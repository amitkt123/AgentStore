import { expectTypeOf, test } from "vitest";
import type { components, paths } from "../src/generated/schema.js";

test("generated schema exposes every runtime path the SDK uses", () => {
  type P = keyof paths;
  const needed: P[] = [
    "/runtime/v1/context", "/runtime/v1/usage", "/runtime/v1/logs", "/runtime/v1/llm/chat",
    "/runtime/v1/heartbeat", "/runtime/v1/gates", "/runtime/v1/gates/{gateId}", "/runtime/v1/fail",
    "/runtime/v1/complete", "/runtime/v1/resources/{resource}/records", "/runtime/v1/resources/{resource}/file",
    "/runtime/v1/resources/{resource}/file/content", "/runtime/v1/resources/{resource}",
  ];
  expectTypeOf(needed).toEqualTypeOf<P[]>();
  expectTypeOf<components["schemas"]["ContextResponse"]["runId"]>().toEqualTypeOf<string | undefined>();
});
