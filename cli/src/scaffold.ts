import { mkdirSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { stringify } from "yaml";

export type ScaffoldRuntime = "python" | "node";

const IGNORE = `node_modules/
__pycache__/
.venv/
*.pyc
.env
`;

// The build only imports (python) or syntax-checks (node) the entrypoint, so all the work sits under main().
const PYTHON_MAIN = `"""AgentStore runs this file with python. The build only imports it, so keep the work under main()."""
import json
import os
import urllib.request


def report(kind, detail=None):
    request = urllib.request.Request(
        os.environ["AGENT_CALLBACK_URL"] + "/events",
        data=json.dumps({"kind": kind, "detail": detail or {}}).encode(),
        headers={
            "Content-Type": "application/json",
            "Authorization": "Bearer " + os.environ["AGENT_CALLBACK_TOKEN"],
        },
        method="POST",
    )
    urllib.request.urlopen(request, timeout=10).read()


def main():
    report("COMPLETE", {"message": "hello from my agent"})


if __name__ == "__main__":
    main()
`;

const NODE_MAIN = `// AgentStore runs this file with node. The build only syntax-checks it.
async function report(kind, detail = {}) {
  const res = await fetch(process.env.AGENT_CALLBACK_URL + "/events", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      Authorization: "Bearer " + process.env.AGENT_CALLBACK_TOKEN,
    },
    body: JSON.stringify({ kind, detail }),
  });
  if (!res.ok) throw new Error("callback failed: " + res.status);
}

await report("COMPLETE", { message: "hello from my agent" });
`;

/**
 * The minimal project: a manifest that passes the backend's schema and bundle rules, a working agent that reports
 * COMPLETE over the callback protocol, and the empty directories the developer platform expects. The Node scaffold
 * is plain ESM JavaScript: the build has no TypeScript step and the backend accepts only .js, .mjs and .cjs
 * entrypoints. It has no package.json, because a package.json needs a package-lock.json next to it.
 */
export function scaffoldFiles(runtime: ScaffoldRuntime, name: string, agentId: string): Record<string, string> {
  const python = runtime === "python";
  const manifest = {
    name,
    version: "0.1.0",
    runtime: python ? "python-3.12" : "node-22",
    entrypoint: python ? "agent/main.py" : "agent/main.mjs",
    scopes: [],
    network: { allowlist: [] },
    costEnvelope: { currency: "INR", maxPerRun: 100, pricingUnit: "METERED", unitPrice: 1, unitLabel: "unit" },
    approvalGates: [],
    dataClasses: [],
  };
  return {
    "agentstore.yaml": stringify({ agentId, name }),
    "manifest.json": JSON.stringify(manifest, null, 2) + "\n",
    ...(python ? { "agent/main.py": PYTHON_MAIN, "requirements.txt": "" } : { "agent/main.mjs": NODE_MAIN }),
    "fixtures/.gitkeep": "",
    "evals/.gitkeep": "",
    "schemas/.gitkeep": "",
    ".agentstoreignore": IGNORE,
  };
}

export function writeScaffold(dir: string, files: Record<string, string>): void {
  for (const [path, content] of Object.entries(files)) {
    const full = join(dir, path);
    mkdirSync(dirname(full), { recursive: true });
    writeFileSync(full, content);
  }
}
