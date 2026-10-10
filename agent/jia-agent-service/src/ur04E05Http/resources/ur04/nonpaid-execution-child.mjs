#!/usr/bin/env node
// SYNTHETIC_EXECUTOR: real OS child via original runCodex/spawn/output parser.
// Probe uses the original parser's required shape ONLY (synthetic 0.0.0). No model/Provider calls. A --version probe is not business capability evidence.
import { appendFile, lstat, readFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { createHash } from 'node:crypto';
import { fileURLToPath } from 'node:url';
const sha = bytes => createHash('sha256').update(bytes).digest('hex');
export function inspectEnvironment(env) {
  const allowed = ['PATH', 'LANG', 'LC_ALL', 'TZ', 'TMPDIR', 'HOME', 'CODEX_HOME',
    'CYF_WORKSPACE_FILE_TOOLCHAIN_PYTHON', 'CYF_WORKSPACE_FILE_DELIVERY_TOOL'];
  const keys = Object.keys(env).sort();
  if (keys.some(key => !allowed.includes(key)) || !env.HOME || env.HOME !== env.CODEX_HOME) throw new Error('CHILD_ENV_REJECTED');
  return keys;
}
export async function execute(env = process.env, argv = process.argv.slice(2)) {
  if (argv.length === 1 && argv[0] === '--version') { process.stdout.write('codex-cli 0.0.0\n'); return; }
  const keys = inspectEnvironment(env);
  if (!argv.includes('exec') || !argv.includes('--json')) throw new Error('CHILD_ORIGINAL_EXEC_ARGUMENTS_REQUIRED');
  const home = resolve(env.HOME);
  const control = JSON.parse(await readFile(resolve(home, 'ur04-child-control.json'), 'utf8'));
  if (typeof control.workspaceRoot !== 'string' || !process.cwd().startsWith(`${control.workspaceRoot}/`)) throw new Error('CHILD_REAL_WORKSPACE_REQUIRED');
  await appendFile(resolve(home, 'ur04-child.jsonl'), JSON.stringify({ synthetic: true, stage: 'CHILD_ENTERED',
    pid: process.pid, parentPid: process.ppid, cwdSha256: sha(process.cwd()), environmentKeys: keys }) + '\n', { mode: 0o600 });
  // Parent releases only after the server observes an actual successful original heartbeat.
  for (;;) {
    try { if ((await lstat(resolve(home, 'ur04-release'))).isFile()) break; }
    catch (error) { if (error.code !== 'ENOENT') throw error; }
    await new Promise(resolveWait => setTimeout(resolveWait, 25));
  }
  process.stdout.write(JSON.stringify({ type: 'item.completed', item: { type: 'agent_message', text: 'UR04 SYNTHETIC_EXECUTOR nonpaid original execution result.' } }) + '\n');
  process.stdout.write(JSON.stringify({ type: 'turn.completed', usage: { input_tokens: 0, output_tokens: 0 } }) + '\n');
}
if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  execute().catch(() => { process.stderr.write('UR04_SYNTHETIC_CHILD_FAILURE\n'); process.exitCode = 1; });
}
