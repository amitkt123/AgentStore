import { describe, it, expect } from 'vitest';
import { spawnSync } from 'node:child_process';
import { mkdtempSync, symlinkSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { handle, shellQuote, isEntryPoint, rangeReadCommand, WRAPPER, CONNECT_TIMEOUT_MS } from './shim.mjs';

const SHIM_PATH = fileURLToPath(new URL('./shim.mjs', import.meta.url));

function fakeSandboxClass({ failRun = false, runStdout = '' } = {}) {
  const calls = [];
  const sandbox = {
    commands: {
      run: async (cmd, opts) => {
        calls.push({ kind: 'run', cmd, opts });
        if (failRun) throw new Error('boom from e2b');
        return { exitCode: 0, stdout: runStdout, stderr: '' };
      },
    },
    files: {
      write: async (path, data) => { calls.push({ kind: 'write', path, data }); },
      read: async () => { throw new Error('files.read must not be used: it downloads the whole file'); },
    },
  };
  return { calls, Sandbox: { connect: async (id, opts) => { calls.push({ kind: 'connect', id, opts }); return sandbox; } } };
}

describe('shellQuote', () => {
  it('single-quotes and escapes embedded quotes', () => {
    expect(shellQuote("it's")).toBe(`'it'\\''s'`);
  });
});

describe('start', () => {
  it('installs the wrapper as root, then launches the command detached with envs', async () => {
    const { calls, Sandbox } = fakeSandboxClass();
    const res = await handle({ op: 'start', sandboxId: 'sbx-1', command: ['sh', '-c', 'echo hi'], env: { TOKEN: 'secret' } },
      { Sandbox, apiKey: 'k' });

    expect(res).toEqual({ ok: true });
    expect(calls[0]).toEqual({ kind: 'connect', id: 'sbx-1', opts: { apiKey: 'k', timeoutMs: 1000 } });
    const write = calls.find((c) => c.kind === 'write');
    expect(write.path).toBe('/workspace/.run/agentstore-run');
    expect(write.data).toBe(WRAPPER);
    const runs = calls.filter((c) => c.kind === 'run');
    expect(runs[0].opts.user).toBe('root');
    const launch = runs[runs.length - 1];
    expect(launch.cmd).toContain(`/workspace/.run/agentstore-run 'sh' '-c' 'echo hi'`);
    expect(launch.cmd).toMatch(/^setsid nohup /);
    expect(launch.opts.envs).toEqual({ TOKEN: 'secret' });
    expect(launch.cmd).not.toContain('secret');
  });

  it('reports failure without echoing env values', async () => {
    const { Sandbox } = fakeSandboxClass({ failRun: true });
    const res = await handle({ op: 'start', sandboxId: 'sbx-1', command: ['true'], env: { TOKEN: 'secret' } },
      { Sandbox, apiKey: 'k' });
    expect(res.ok).toBe(false);
    expect(JSON.stringify(res)).not.toContain('secret');
  });
});

describe('readFile', () => {
  it('reads only the requested byte range with one command', async () => {
    const { calls, Sandbox } = fakeSandboxClass({ runStdout: `11\n${Buffer.from('wor').toString('base64')}` });
    const res = await handle({ op: 'readFile', sandboxId: 's', path: '/workspace/.run/log', offset: 6, maxBytes: 3 },
      { Sandbox, apiKey: 'k' });
    expect(res).toEqual({ ok: true, exists: true, data: Buffer.from('wor').toString('base64'), size: 11 });
    const run = calls.find((c) => c.kind === 'run');
    expect(run.cmd).toBe(rangeReadCommand('/workspace/.run/log', 6, 3));
    expect(run.cmd).toContain('tail -c +7');
    expect(run.cmd).toContain('head -c 3');
  });

  it('reports a missing file as exists:false', async () => {
    const { Sandbox } = fakeSandboxClass({ runStdout: 'MISSING\n' });
    const res = await handle({ op: 'readFile', sandboxId: 's', path: '/workspace/.run/exit', offset: 0, maxBytes: 10 },
      { Sandbox, apiKey: 'k' });
    expect(res).toEqual({ ok: true, exists: false });
  });

  it('returns empty data when the offset is at the end of the file', async () => {
    const { Sandbox } = fakeSandboxClass({ runStdout: '5\n' });
    const res = await handle({ op: 'readFile', sandboxId: 's', path: '/workspace/.run/log', offset: 5, maxBytes: 10 },
      { Sandbox, apiKey: 'k' });
    expect(res).toEqual({ ok: true, exists: true, data: '', size: 5 });
  });

  it('quotes the path', () => {
    expect(rangeReadCommand("/tmp/a'b", 0, 1)).toContain(`'/tmp/a'\\''b'`);
  });
});

describe('connect timeout', () => {
  // Omitting timeoutMs makes the connect call reset a running sandbox's expiry to >= 300s.
  it('passes a 1s timeoutMs on every connect so it never extends the sandbox lifetime', async () => {
    expect(CONNECT_TIMEOUT_MS).toBe(1000);
    const { calls, Sandbox } = fakeSandboxClass({ runStdout: '1\neA==' });
    await handle({ op: 'start', sandboxId: 'a', command: ['true'] }, { Sandbox, apiKey: 'k' });
    await handle({ op: 'readFile', sandboxId: 'b', path: '/workspace/.run/log', offset: 0, maxBytes: 1 },
      { Sandbox, apiKey: 'k' });
    const connects = calls.filter((c) => c.kind === 'connect');
    expect(connects).toHaveLength(2);
    for (const c of connects) expect(c.opts).toEqual({ apiKey: 'k', timeoutMs: 1000 });
  });
});

describe('entry point guard', () => {
  it('matches the module URL for the real path and rejects others', () => {
    expect(isEntryPoint(SHIM_PATH, pathToFileURL(SHIM_PATH).href)).toBe(true);
    expect(isEntryPoint(undefined, pathToFileURL(SHIM_PATH).href)).toBe(false);
    expect(isEntryPoint('/no/such/file.mjs', pathToFileURL(SHIM_PATH).href)).toBe(false);
  });

  it('runs main when started through a symlink in a directory whose name has a space', () => {
    const dir = mkdtempSync(join(tmpdir(), 'shim entry '));
    try {
      const link = join(dir, 'shim.mjs');
      symlinkSync(SHIM_PATH, link);
      const res = spawnSync(process.execPath, [link], { input: 'not json', encoding: 'utf8' });
      expect(JSON.parse(res.stdout)).toMatchObject({ ok: false, error: { code: 'BAD_REQUEST' } });
      expect(res.status).toBe(1);
    } finally {
      rmSync(dir, { recursive: true, force: true });
    }
  });
});

describe('unknown op', () => {
  it('fails with BAD_REQUEST', async () => {
    const { Sandbox } = fakeSandboxClass();
    const res = await handle({ op: 'nope' }, { Sandbox, apiKey: 'k' });
    expect(res).toMatchObject({ ok: false, error: { code: 'BAD_REQUEST' } });
  });
});
