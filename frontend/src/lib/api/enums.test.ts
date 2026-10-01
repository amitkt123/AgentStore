import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { BUILD_STATUSES, DATA_FORMATS, LLM_MODES, PRICING_UNITS, SCOPE_TYPES, VERSION_STATUSES } from "./enums";

// Vitest runs from frontend/, so the backend sits one level up.
const platform = resolve(process.cwd(), "../backend/src/main/java/com/agentstore/platform");

function javaEnum(file: string, name: string): string[] {
  const src = readFileSync(resolve(platform, `${file}.java`), "utf8");
  const body = src.slice(src.indexOf("{", src.indexOf(`enum ${name}`)) + 1).split(/[;}]/)[0];
  return body.split(",").map((s) => s.trim().match(/^[A-Z_]+/)?.[0]).filter((s): s is string => !!s);
}

it.each([
  ["ScopeType", "manifest/ScopeType", SCOPE_TYPES],
  ["DataFormat", "manifest/DataFormat", DATA_FORMATS],
  ["PricingUnit", "manifest/PricingUnit", PRICING_UNITS],
  ["LlmMode", "manifest/LlmMode", LLM_MODES],
  ["VersionStatus", "catalog/VersionStatus", VERSION_STATUSES],
  ["BuildStatus", "build/BuildStatus", BUILD_STATUSES],
])("%s matches the backend enum", (name, file, values) => {
  expect([...values]).toEqual(javaEnum(file, name));
});
