import { readFileSync } from "fs";
import path from "path";

// The PAN image can be 5 MB; Next's default server-action body limit is 1 MB.
it("raises the server action body limit above the 5 MB PAN image", () => {
  const text = readFileSync(path.resolve(__dirname, "../next.config.mjs"), "utf8");
  expect(text).toMatch(/bodySizeLimit:\s*"6mb"/);
});
