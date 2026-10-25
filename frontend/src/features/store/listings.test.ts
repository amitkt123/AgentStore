import { latestPerAgent } from "./listings";
import type { CatalogListing } from "@/lib/api/types";

const l = (agentId: string, versionNumber: string): CatalogListing => ({ agentId, agentName: agentId, category: "c", versionId: `${agentId}-${versionNumber}`, versionNumber });

it("keeps one listing per agent, the highest version compared numerically", () => {
  expect(latestPerAgent([l("a", "0.9.0"), l("a", "0.10.0"), l("b", "1.0.0")]).map((x) => x.versionNumber)).toEqual(["0.10.0", "1.0.0"]);
});

it("keeps the first agent order", () => {
  expect(latestPerAgent([l("b", "1.0.0"), l("a", "0.1.0"), l("b", "1.1.0")]).map((x) => x.agentId)).toEqual(["b", "a"]);
});
