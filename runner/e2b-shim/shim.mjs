// Adapter for the E2B operations that exist only in E2B's JS SDK (spec §2.3).
// Protocol: one JSON request on stdin, one JSON response on stdout, exit 0 iff ok.
// Never print the request or env values: they carry run secrets.

import { appendFileSync, realpathSync } from 'node:fs';
import { pathToFileURL } from 'node:url';

export const RUN_DIR = '/workspace/.run';
export const WRAPPER_PATH = `${RUN_DIR}/agentstore-run`;
export const WRAPPER = `#!/bin/sh
"$@" >>${RUN_DIR}/log 2>&1
code=$?
printf '%s' "$code" >${RUN_DIR}/exit.tmp && mv ${RUN_DIR}/exit.tmp ${RUN_DIR}/exit
exit "$code"
`;

export function shellQuote(s) {
  return `'${String(s).replace(/'/g, `'\\''`)}'`;
}

// Every Sandbox.connect must pass a timeout. SDK 2.51.0 always POSTs /v2/sandboxes/{id}/connect,
// and an omitted timeout means the server default of 300s. The SDK and the API document that for a
// running sandbox the timeout "will update only if the new timeout is longer than the existing one".
// So a tiny value (1s, the smallest whole second the SDK sends) never shortens or extends the
// lifetime the runner set at create time. Without it, every shim call would push expiry out to >=300s.
export const CONNECT_TIMEOUT_MS = 1000;

function connect(Sandbox, sandboxId, apiKey) {
  return Sandbox.connect(sandboxId, { apiKey, timeoutMs: CONNECT_TIMEOUT_MS });
}

function fail(code, message) {
  return { ok: false, error: { code, message } };
}

async function start(req, { Sandbox, apiKey }) {
  const sbx = await connect(Sandbox, req.sandboxId, apiKey);
  await sbx.commands.run(`mkdir -p ${RUN_DIR} && chmod 777 ${RUN_DIR} && touch ${RUN_DIR}/log && chmod 666 ${RUN_DIR}/log`,
    { user: 'root' });
  await sbx.files.write(WRAPPER_PATH, WRAPPER);
  await sbx.commands.run(`chmod 755 ${WRAPPER_PATH}`, { user: 'root' });
  const argv = req.command.map(shellQuote).join(' ');
  // Detached so the agent keeps running after this shim process (and its SDK connection) exits.
  // `run()` without `background: true` waits for the shell to exit (SDK default timeout 60s);
  // the trailing `&` backgrounds the launched process inside that shell, so the shell itself
  // returns almost immediately with exit code 0 and `run()` does not throw CommandExitError.
  await sbx.commands.run(`setsid nohup ${WRAPPER_PATH} ${argv} >/dev/null 2>&1 &`, { envs: req.env ?? {} });
  return { ok: true };
}

// Reads [offset, offset + maxBytes) of a file inside the sandbox with one shell command, so a log poll never
// downloads the whole file. Output: "<size>\n<base64 slice>", or "MISSING".
export function rangeReadCommand(path, offset, maxBytes) {
  const p = shellQuote(path);
  const start = Math.max(0, Math.floor(offset)) + 1;
  const count = Math.max(0, Math.floor(maxBytes));
  return `if [ -f ${p} ]; then stat -c %s ${p} && tail -c +${start} ${p} | head -c ${count} | base64 -w0; else echo MISSING; fi`;
}

async function readFile(req, { Sandbox, apiKey }) {
  const sbx = await connect(Sandbox, req.sandboxId, apiKey);
  const res = await sbx.commands.run(rangeReadCommand(req.path, req.offset ?? 0, req.maxBytes ?? 262144), { user: 'root' });
  const out = String(res?.stdout ?? '');
  const newline = out.indexOf('\n');
  const first = (newline < 0 ? out : out.slice(0, newline)).trim();
  if (first === 'MISSING') return { ok: true, exists: false };
  const size = Number(first);
  if (!Number.isFinite(size)) throw new Error('unexpected readFile output');
  return { ok: true, exists: true, data: newline < 0 ? '' : out.slice(newline + 1).trim(), size };
}

// ---- Image build (spec §4 steps 4-5). The same recipe is what WS5d's gVisor backend implements. ----

export const APP_DIR = '/workspace/app';
export const FAIL_MARK = 'AGENTSTORE_STEP_FAILED:';
export const BASE_IMAGES = { 'python-3.12': 'python:3.12-slim', 'node-22': 'node:22-slim' };

// Only these codes are ever emitted by a recipe step; matched strictly (a real marker line is never followed by
// a word character or a quote) so a step's own command text - which some build backends echo into the log before
// running it - can never be mistaken for the step's real, post-execution failure output.
const STEP_FAIL_CODES = ['INSTALL_FAILED', 'DEP_VULN', 'SMOKE_FAILED'];
const FAIL_MARK_PATTERN = new RegExp(`${FAIL_MARK}(${STEP_FAIL_CODES.join('|')})(?![\\w"])`);

// Imports the entrypoint under a name other than __main__, so `if __name__ == "__main__":` code does not run.
// sys.path[0] is set to the entrypoint's directory, as `python <entrypoint>` would do at run time.
export const PY_SMOKE = "import importlib.util as u, os, sys; sys.path[0] = os.path.dirname(sys.argv[1]); "
  + "s = u.spec_from_file_location('agentstore_smoke', sys.argv[1]); s.loader.exec_module(u.module_from_spec(s))";

// Runs one recipe step; on failure prints the marker the runner maps to a failure code. The code is a separate
// printf argument, so the step's own command TEXT never contains the joined "<FAIL_MARK><CODE>" literal - only
// the executed printf's actual stdout does. See FAIL_MARK_PATTERN above for why that also matters.
function step(code, script) {
  return `sh -c ${shellQuote(script)} || { printf '${FAIL_MARK}%s\\n' '${code}'; exit 1; }`;
}

export function recipe(runtime, entrypoint) {
  const ep = shellQuote(`${APP_DIR}/${entrypoint}`);
  if (runtime === 'python-3.12') {
    return [
      step('INSTALL_FAILED', `cd ${APP_DIR} && if [ -f requirements.txt ]; then pip install --no-cache-dir -r requirements.txt; fi`),
      step('DEP_VULN', `cd ${APP_DIR} && if [ -f requirements.txt ]; then python -m venv /opt/agentstore-audit`
        + ` && /opt/agentstore-audit/bin/pip install --no-cache-dir --quiet pip-audit`
        + ` && /opt/agentstore-audit/bin/pip-audit --progress-spinner off -r requirements.txt; rc=$?;`
        + ` rm -rf /opt/agentstore-audit; exit $rc; fi`),
      step('SMOKE_FAILED', `cd ${APP_DIR} && timeout 60 python -c ${shellQuote(PY_SMOKE)} ${ep}`),
    ];
  }
  if (runtime === 'node-22') {
    return [
      step('INSTALL_FAILED', `cd ${APP_DIR} && if [ -f package.json ]; then`
        + ` [ -f package-lock.json ] || { echo "package-lock.json is required next to package.json"; exit 1; };`
        + ` npm ci --omit=dev; fi`),
      step('DEP_VULN', `cd ${APP_DIR} && if [ -f package.json ]; then npm audit --omit=dev --audit-level=high; fi`),
      step('SMOKE_FAILED', `cd ${APP_DIR} && node --check ${ep}`),
    ];
  }
  throw new Error(`unknown runtime ${runtime}`);
}

function lineWriter(path) {
  return (line) => {
    if (path) appendFileSync(path, `${String(line).replace(/\n+$/, '')}\n`);
  };
}

async function build(req, { Template, apiKey }) {
  const base = BASE_IMAGES[req.runtime];
  if (!base) return fail('BAD_REQUEST', `unknown runtime ${String(req.runtime)}`);
  const writeLog = lineWriter(req.logPath);
  let template = Template({ fileContextPath: req.appDir })
    .fromImage(base)
    .runCmd(`mkdir -p ${APP_DIR}`, { user: 'root' })
    .copy('.', APP_DIR)
    .runCmd(`chmod -R a+rX ${APP_DIR}`, { user: 'root' });
  for (const cmd of recipe(req.runtime, req.entrypoint)) {
    template = template.runCmd(cmd, { user: 'root' });
  }
  const failedSteps = [];
  try {
    const info = await Template.build(template, req.name, {
      apiKey,
      cpuCount: req.cpu ?? 1,
      memoryMB: req.memoryMb ?? 1024,
      onBuildLogs: (entry) => {
        const line = String(entry?.message ?? entry);
        writeLog(line);
        const match = FAIL_MARK_PATTERN.exec(line);
        if (match) failedSteps.push(match[1]);
      },
    });
    return { ok: true, imageRef: info.templateId };
  } catch (e) {
    return fail(failedSteps[0] ?? 'BUILD_FAILED',
      `build failed: ${e?.name ?? 'Error'}: ${e?.message ?? ''}`.slice(0, 500));
  }
}

export async function handle(req, deps) {
  try {
    switch (req?.op) {
      case 'start': return await start(req, deps);
      case 'readFile': return await readFile(req, deps);
      case 'build': return await build(req, deps);
      default: return fail('BAD_REQUEST', `unknown op ${String(req?.op)}`);
    }
  } catch (e) {
    return fail('E2B_ERROR', `${req?.op} failed: ${e?.name ?? 'Error'}: ${e?.message ?? ''}`.slice(0, 500));
  }
}

async function main() {
  const chunks = [];
  for await (const c of process.stdin) chunks.push(c);
  let req;
  try {
    req = JSON.parse(Buffer.concat(chunks).toString('utf8'));
  } catch {
    process.stdout.write(JSON.stringify(fail('BAD_REQUEST', 'stdin is not JSON')));
    process.exit(1);
  }
  const { Sandbox, Template } = await import('e2b');
  const res = await handle(req, { Sandbox, Template, apiKey: process.env.E2B_API_KEY });
  process.stdout.write(JSON.stringify(res));
  process.exit(res.ok ? 0 : 1);
}

// True when this file is the script node was started with. It resolves symlinks and
// percent-encodes the path the same way node builds import.meta.url for the main module.
export function isEntryPoint(argv1 = process.argv[1], moduleUrl = import.meta.url) {
  if (!argv1) return false;
  try {
    return pathToFileURL(realpathSync(argv1)).href === moduleUrl;
  } catch {
    return false;
  }
}

if (isEntryPoint()) {
  main();
}
