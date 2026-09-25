import { describe, it, expect } from 'vitest';
import { handle, shellQuote, WRAPPER } from './shim.mjs';

function fakeSandboxClass({ files = {}, failRun = false } = {}) {
  const calls = [];
  const sandbox = {
    commands: {
      run: async (cmd, opts) => {
        calls.push({ kind: 'run', cmd, opts });
        if (failRun) throw new Error('boom from e2b');
        return { exitCode: 0 };
      },
    },
    files: {
      write: async (path, data) => { calls.push({ kind: 'write', path, data }); },
      read: async (path) => {
        if (!(path in files)) { const e = new Error('not found'); e.name = 'NotFoundError'; throw e; }
        return new TextEncoder().encode(files[path]);
      },
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
    expect(calls[0]).toMatchObject({ kind: 'connect', id: 'sbx-1', opts: { apiKey: 'k' } });
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
  it('returns base64 slice from offset capped at maxBytes, plus total size', async () => {
    const { Sandbox } = fakeSandboxClass({ files: { '/workspace/.run/log': 'hello world' } });
    const res = await handle({ op: 'readFile', sandboxId: 's', path: '/workspace/.run/log', offset: 6, maxBytes: 3 },
      { Sandbox, apiKey: 'k' });
    expect(res).toEqual({ ok: true, exists: true, data: Buffer.from('wor').toString('base64'), size: 11 });
  });

  it('reports a missing file as exists:false', async () => {
    const { Sandbox } = fakeSandboxClass();
    const res = await handle({ op: 'readFile', sandboxId: 's', path: '/workspace/.run/exit', offset: 0, maxBytes: 10 },
      { Sandbox, apiKey: 'k' });
    expect(res).toEqual({ ok: true, exists: false });
  });
});

describe('unknown op', () => {
  it('fails with BAD_REQUEST', async () => {
    const { Sandbox } = fakeSandboxClass();
    const res = await handle({ op: 'nope' }, { Sandbox, apiKey: 'k' });
    expect(res).toMatchObject({ ok: false, error: { code: 'BAD_REQUEST' } });
  });
});
