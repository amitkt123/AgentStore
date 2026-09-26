import { describe, it, expect } from 'vitest';
import { spawnSync } from 'node:child_process';
import { mkdtempSync, symlinkSync, rmSync, readFileSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { handle, shellQuote, isEntryPoint, rangeReadCommand, WRAPPER, CONNECT_TIMEOUT_MS,
  recipe, BASE_IMAGES, FAIL_MARK } from './shim.mjs';

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

function fakeTemplate({ logs = [], fail = null } = {}) {
  const calls = [];
  const builder = {
    fromImage(image) { calls.push(['fromImage', image]); return builder; },
    runCmd(cmd, opts) { calls.push(['runCmd', cmd, opts]); return builder; },
    copy(src, dest) { calls.push(['copy', src, dest]); return builder; },
  };
  const Template = (opts) => { calls.push(['Template', opts]); return builder; };
  Template.build = async (template, name, opts) => {
    calls.push(['build', name, { apiKey: opts.apiKey, cpuCount: opts.cpuCount, memoryMB: opts.memoryMB }]);
    for (const message of logs) opts.onBuildLogs({ message });
    if (fail) throw fail;
    return { templateId: 'tpl-123', buildId: 'b-1', name, alias: name, tags: [] };
  };
  return { calls, Template };
}

describe('recipe', () => {
  it('installs, audits and smoke-tests a python app in that order, each step tagged with its failure code', () => {
    const steps = recipe('python-3.12', 'agent/main.py');
    expect(steps).toHaveLength(3);
    expect(steps[0]).toContain('pip install --no-cache-dir -r requirements.txt');
    expect(steps[0]).toContain('INSTALL_FAILED');
    expect(steps[1]).toContain('pip-audit');
    expect(steps[1]).toContain('DEP_VULN');
    expect(steps[2]).toContain('/workspace/app/agent/main.py');
    expect(steps[2]).toContain('SMOKE_FAILED');
  });

  it('uses npm ci, npm audit and node --check for node', () => {
    const steps = recipe('node-22', 'index.mjs');
    expect(steps[0]).toContain('npm ci --omit=dev');
    expect(steps[1]).toContain('npm audit --omit=dev --audit-level=high');
    expect(steps[2]).toContain('node --check');
  });

  it('rejects unknown runtimes', () => {
    expect(() => recipe('ruby-3', 'main.rb')).toThrow(/unknown runtime/);
  });

  it('never embeds the joined marker+code literal in a step\'s own command text', () => {
    for (const [runtime, entrypoint] of [['python-3.12', 'agent/main.py'], ['node-22', 'index.mjs']]) {
      for (const cmd of recipe(runtime, entrypoint)) {
        expect(cmd).not.toContain(`${FAIL_MARK}INSTALL_FAILED`);
        expect(cmd).not.toContain(`${FAIL_MARK}DEP_VULN`);
        expect(cmd).not.toContain(`${FAIL_MARK}SMOKE_FAILED`);
      }
    }
  });
});

describe('build', () => {
  const tmp = () => mkdtempSync(join(tmpdir(), 'shim-build-'));

  it('builds from the app directory on the runtime base image and returns the image ref', async () => {
    const dir = tmp();
    const logPath = join(dir, 'build.log');
    writeFileSync(logPath, '');
    const { calls, Template } = fakeTemplate({ logs: ['step 1/5', 'step 2/5'] });
    const res = await handle({ op: 'build', name: 'agentstore-b1', appDir: '/tmp/app', runtime: 'python-3.12',
      entrypoint: 'main.py', logPath, cpu: 1, memoryMb: 1024 }, { Template, apiKey: 'k' });

    expect(res).toEqual({ ok: true, imageRef: 'tpl-123' });
    expect(calls[0]).toEqual(['Template', { fileContextPath: '/tmp/app' }]);
    expect(calls).toContainEqual(['fromImage', BASE_IMAGES['python-3.12']]);
    expect(calls).toContainEqual(['copy', '.', '/workspace/app']);
    expect(calls.filter((c) => c[0] === 'runCmd').every((c) => c[2]?.user === 'root')).toBe(true);
    expect(calls.at(-1)).toEqual(['build', 'agentstore-b1', { apiKey: 'k', cpuCount: 1, memoryMB: 1024 }]);
    expect(readFileSync(logPath, 'utf8')).toBe('step 1/5\nstep 2/5\n');
    rmSync(dir, { recursive: true, force: true });
  });

  it('reports the failing step code from the build log marker', async () => {
    const dir = tmp();
    const { Template } = fakeTemplate({ logs: ['found 2 vulnerabilities', `${FAIL_MARK}DEP_VULN`],
      fail: Object.assign(new Error('step failed'), { name: 'BuildError' }) });
    const res = await handle({ op: 'build', name: 'n', appDir: '/a', runtime: 'python-3.12', entrypoint: 'main.py',
      logPath: join(dir, 'l.log') }, { Template, apiKey: 'k' });
    expect(res.ok).toBe(false);
    expect(res.error.code).toBe('DEP_VULN');
    expect(res.error.message).toContain('BuildError');
    rmSync(dir, { recursive: true, force: true });
  });

  it('is not fooled by a build backend echoing a step\'s own command text into the log, and reports the real marker', async () => {
    const dir = tmp();
    const [installStep] = recipe('python-3.12', 'agent/main.py');
    const { Template } = fakeTemplate({
      logs: [
        `Step 3/8 : RUN ${installStep}`,   // the backend echoing the command line it is about to run
        `${FAIL_MARK}DEP_VULN`,            // the real failure, from a later step
      ],
      fail: Object.assign(new Error('step failed'), { name: 'BuildError' }),
    });
    const res = await handle({ op: 'build', name: 'n', appDir: '/a', runtime: 'python-3.12', entrypoint: 'main.py',
      logPath: join(dir, 'l.log') }, { Template, apiKey: 'k' });
    expect(res.ok).toBe(false);
    expect(res.error.code).toBe('DEP_VULN');
    rmSync(dir, { recursive: true, force: true });
  });

  it('reports BUILD_FAILED when no step marker was seen', async () => {
    const dir = tmp();
    const { Template } = fakeTemplate({ fail: new Error('upload failed') });
    const res = await handle({ op: 'build', name: 'n', appDir: '/a', runtime: 'node-22', entrypoint: 'index.js',
      logPath: join(dir, 'l.log') }, { Template, apiKey: 'k' });
    expect(res).toMatchObject({ ok: false, error: { code: 'BUILD_FAILED' } });
    rmSync(dir, { recursive: true, force: true });
  });

  it('rejects an unknown runtime without building', async () => {
    const { calls, Template } = fakeTemplate();
    const res = await handle({ op: 'build', name: 'n', appDir: '/a', runtime: 'ruby-3', entrypoint: 'x.rb' },
      { Template, apiKey: 'k' });
    expect(res).toMatchObject({ ok: false, error: { code: 'BAD_REQUEST' } });
    expect(calls).toHaveLength(0);
  });
});
