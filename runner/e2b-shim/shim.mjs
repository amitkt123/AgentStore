// Adapter for the E2B operations that exist only in E2B's JS SDK (spec §2.3).
// Protocol: one JSON request on stdin, one JSON response on stdout, exit 0 iff ok.
// Never print the request or env values: they carry run secrets.

import { realpathSync } from 'node:fs';
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

// The installed e2b SDK (2.x) throws `FileNotFoundError` (a subclass of the now-deprecated
// `NotFoundError`) for a missing file; its `.name` is `'FileNotFoundError'`, not `'NotFoundError'`.
// We check both names so this also matches the unit tests' fake, which throws with
// `.name = 'NotFoundError'`, and any older SDK that still throws the base class directly.
function isMissingFileError(e) {
  return e?.name === 'FileNotFoundError' || e?.name === 'NotFoundError' || /not found/i.test(e?.message ?? '');
}

async function readFile(req, { Sandbox, apiKey }) {
  const sbx = await connect(Sandbox, req.sandboxId, apiKey);
  let bytes;
  try {
    bytes = await sbx.files.read(req.path, { format: 'bytes' });
  } catch (e) {
    if (isMissingFileError(e)) return { ok: true, exists: false };
    throw e;
  }
  const offset = Math.max(0, req.offset ?? 0);
  const slice = Buffer.from(bytes).subarray(offset, offset + (req.maxBytes ?? 262144));
  return { ok: true, exists: true, data: slice.toString('base64'), size: bytes.length };
}

export async function handle(req, deps) {
  try {
    switch (req?.op) {
      case 'start': return await start(req, deps);
      case 'readFile': return await readFile(req, deps);
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
  const { Sandbox } = await import('e2b');
  const res = await handle(req, { Sandbox, apiKey: process.env.E2B_API_KEY });
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
