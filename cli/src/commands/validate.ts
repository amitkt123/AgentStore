import type { Context } from "../context.js";
import { validateProject } from "../validate.js";

export async function validateCommand(ctx: Context): Promise<number> {
  const { problems, bundle } = validateProject(ctx.cwd);
  if (!bundle) {
    ctx.err(`Found ${problems.length} problem${problems.length === 1 ? "" : "s"}:`);
    for (const problem of problems) {
      ctx.err(`  - ${problem}`);
    }
    return 1;
  }
  ctx.out(`OK: ${bundle.entries.length} files, ${(bundle.bytes.length / 1024).toFixed(1)} KiB packed.`);
  return 0;
}
