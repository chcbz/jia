#!/usr/bin/env node
// UR06 private pipe client: default real fetch/ws, original host/adapter/engine.
// No fake server/client/socket/executor/lease/result. External clean artifact required.
import { createHash, randomUUID } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { createInterface } from 'node:readline';
import { chmod, lstat, mkdir, readFile, readdir, readlink, realpath, writeFile } from 'node:fs/promises';
import { isAbsolute, resolve } from 'node:path';
import { pathToFileURL, fileURLToPath } from 'node:url';
export const SOURCE = Object.freeze({ commit: '7594fd72251d38b6e1d23a1a3cca184ae0d085e7',
  tree: '0827ce904179862ab55648fe99b780cea94faf98', count: 285,
  tarSha256: 'f4dcd014e409b5a0e32f7419cd9500bf1f3e0022492f533736b366cd325f0106', tarBytes: 3594240 });
const PREFIX = 'UR06_PIPE ';
let phase = 'PRIVATE_INPUT';
const SAFE_CODES = new Set(["ARTIFACT_ALIAS_FORBIDDEN", "ARTIFACT_FULL_INVENTORY_REQUIRED", "ARTIFACT_LINK_PROOF_REQUIRED", "ARTIFACT_MEMBER_MISMATCH", "CLEAN_ARTIFACT_EVIDENCE_REQUIRED", "CLIENT_COMMIT_REQUIRED", "CURRENT_TRANSPORT_REQUIRED", "ENGINE_SOURCE_BINDING_REQUIRED", "EXACT_AGENT_INDEX_REQUIRED", "EXPLICIT_STOP_REQUIRED", "MEMBER_UNSAFE", "NODE_20_20_2_REQUIRED", "NODE_ARTIFACT_BINARY_BINDING_REQUIRED", "OWNED_SUBJECT_SOCKET_REQUIRED", "PATH_ALIAS_FORBIDDEN", "PATH_CANONICAL_REQUIRED", "PATH_TYPE_REQUIRED", "PRIVATE_CHILD_ONLY", "PRIVATE_ENROLL_START_REQUIRED", "PRIVATE_INIT_REQUIRED", "PRIVATE_ORIGIN_REQUIRED", "PRIVATE_STATE_MODE_REQUIRED", "PRIVATE_STATE_NAME_REQUIRED", "REAL_CODEX_EXECUTABLE_REQUIRED", "RUNTIME_SOURCE_BINDING_REQUIRED", "SOURCE_ALIAS_FORBIDDEN", "SOURCE_ARCHIVE_MISMATCH", "SOURCE_FULL_TREE_MISMATCH", "SOURCE_IDENTITY_REQUIRED", "SOURCE_MEMBER_HASH_REQUIRED", "SOURCE_MEMBER_MISMATCH", "SOURCE_UNEXPECTED_MEMBER", "UNKNOWN_PRIVATE_OPERATION"]);
const sha = b => createHash('sha256').update(b).digest('hex');
const object = v => v !== null && typeof v === 'object' && !Array.isArray(v);
const fail = code => Object.assign(new Error(code), { code });
const requireInput = (ok, code) => { if (!ok) throw fail(code); };
export function member(name) {
  requireInput(typeof name === 'string' && name && !name.startsWith('/') && !/[\\\x00-\x1f\x7f]/.test(name)
    && name.split('/').every(p => p && !['.', '..', '.git'].includes(p)), 'MEMBER_UNSAFE'); return name;
}
const gitObject = (type, bytes) => createHash('sha1').update(Buffer.concat([Buffer.from(`${type} ${bytes.length}\0`), bytes])).digest('hex');
export function treeDigest(entries) {
  const sorted = [...entries].sort((a, b) => Buffer.compare(Buffer.from(a.name + (a.directory ? '/' : '')), Buffer.from(b.name + (b.directory ? '/' : ''))));
  return gitObject('tree', Buffer.concat(sorted.map(e => Buffer.concat([Buffer.from(`${e.mode} ${e.name}\0`), Buffer.from(e.blob, 'hex')]))));
}
async function canonical(path, directory = true) {
  requireInput(typeof path === 'string' && isAbsolute(path) && resolve(path) === path && !/[\x00-\x1f]/.test(path), 'PATH_CANONICAL_REQUIRED');
  requireInput(await realpath(path) === path, 'PATH_ALIAS_FORBIDDEN');
  const st = await lstat(path); requireInput(directory ? st.isDirectory() : st.isFile(), 'PATH_TYPE_REQUIRED'); return path;
}
async function json(path) { return JSON.parse(await readFile(path, 'utf8')); }
async function privateJson(path, value) { await writeFile(path, JSON.stringify(value) + '\n', { mode: 0o600 }); await chmod(path, 0o600); }
function validateSourceIdentity(proof) {
  requireInput(object(proof) && proof.format === 'ur04-full-git-archive-v1' && proof.commit === SOURCE.commit
    && proof.tree === SOURCE.tree && proof.archiveSha256 === SOURCE.tarSha256 && proof.archiveBytes === SOURCE.tarBytes
    && object(proof.files) && Object.keys(proof.files).length === SOURCE.count, 'SOURCE_IDENTITY_REQUIRED');
  for (const [name, hash] of Object.entries(proof.files)) requireInput(member(name) && /^[0-9a-f]{64}$/.test(hash), 'SOURCE_MEMBER_HASH_REQUIRED');
}
export async function verifyInputs({ sourceRoot, artifactRoot, clientCommit }) {
  requireInput(clientCommit === SOURCE.commit, 'CLIENT_COMMIT_REQUIRED');
  await canonical(sourceRoot); await canonical(artifactRoot);
  phase = 'SOURCE_PROOF';
  const proof = await json(resolve(sourceRoot, 'ur04-client-source.json')); validateSourceIdentity(proof);
  const archive = await readFile(await canonical(proof.archivePath, false));
  requireInput(archive.length === SOURCE.tarBytes && sha(archive) === SOURCE.tarSha256, 'SOURCE_ARCHIVE_MISMATCH');
  // The immutable raw git archive hash binds its PAX commit too; reconstruct the whole Git tree.
  const found = new Set();
  async function inventory(directory, prefix = '') {
    const entries = [];
    for (const name of await readdir(directory)) {
      if (!prefix && name === 'ur04-client-source.json') continue;
      const relative = member(prefix + name), path = resolve(directory, name), st = await lstat(path);
      requireInput(!st.isSymbolicLink(), 'SOURCE_ALIAS_FORBIDDEN');
      if (st.isDirectory()) entries.push({ name, directory: true, mode: '40000', blob: await inventory(path, `${relative}/`) });
      else {
        requireInput(st.isFile() && Object.hasOwn(proof.files, relative), 'SOURCE_UNEXPECTED_MEMBER');
        const bytes = await readFile(path); requireInput(sha(bytes) === proof.files[relative], 'SOURCE_MEMBER_MISMATCH');
        found.add(relative); entries.push({ name, mode: st.mode & 0o111 ? '100755' : '100644', blob: gitObject('blob', bytes) });
      }
    }
    return treeDigest(entries);
  }
  requireInput(await inventory(sourceRoot) === SOURCE.tree && found.size === SOURCE.count, 'SOURCE_FULL_TREE_MISMATCH');
  phase = 'ARTIFACT_PROOF';
  const artifact = await json(resolve(artifactRoot, 'ur04-clean-artifact.json'));
  requireInput(object(artifact) && artifact.format === 'ur04-clean-artifact-v1' && artifact.sourceCommit === SOURCE.commit
    && artifact.sourceTree === SOURCE.tree && artifact.archiveSha256 === SOURCE.tarSha256
    && typeof artifact.cleanRun === 'string' && /^[A-Za-z0-9._:-]+$/.test(artifact.cleanRun)
    && ['C1', 'C2', 'C3', 'C4'].every(key => artifact.checks?.[key] === 'PASS') && object(artifact.files)
    && Object.keys(artifact.files).length > 0, 'CLEAN_ARTIFACT_EVIDENCE_REQUIRED');
  const artifactFound = new Set();
  async function artifactInventory(directory, prefix = '') {
    for (const name of await readdir(directory)) {
      if (!prefix && name === 'ur04-clean-artifact.json') continue;
      const relative = member(prefix + name), path = resolve(directory, name), st = await lstat(path);
      if (st.isDirectory()) { requireInput(!st.isSymbolicLink(), 'ARTIFACT_ALIAS_FORBIDDEN'); await artifactInventory(path, `${relative}/`); }
      else if (st.isSymbolicLink()) {
        // Venv symlinks are allowed only when explicitly proven and resolved within this artifact.
        const expected = artifact.files[relative];
        requireInput(object(expected) && expected.type === 'symlink' && (await realpath(path)).startsWith(`${artifactRoot}/`)
          && typeof expected.target === 'string' && await readlink(path) === expected.target
          && sha(Buffer.from(expected.target)) === expected.sha256, 'ARTIFACT_LINK_PROOF_REQUIRED'); artifactFound.add(relative);
      } else {
        requireInput(st.isFile() && /^[0-9a-f]{64}$/.test(artifact.files[relative])
          && sha(await readFile(path)) === artifact.files[relative], 'ARTIFACT_MEMBER_MISMATCH'); artifactFound.add(relative);
      }
    }
  }
  await artifactInventory(artifactRoot);
  requireInput(artifactFound.size === Object.keys(artifact.files).length, 'ARTIFACT_FULL_INVENTORY_REQUIRED');
  phase = 'ORIGINAL_PAYLOAD_VALIDATE';
  const { EXECUTION_PAYLOAD_FILES, validateExecutionPayload } = await import(pathToFileURL(resolve(sourceRoot, 'conf/cyf-agent-runtime-v1/lib/execution-adapter.mjs')));
  for (const file of EXECUTION_PAYLOAD_FILES) requireInput(sha(await readFile(resolve(artifactRoot, 'codex-ws-agent', file)))
    === proof.files[`conf/codex-ws-agent/${file}`], 'ENGINE_SOURCE_BINDING_REQUIRED');
  for (const file of ['agent-runtime.mjs', 'lib/manifest.mjs', 'lib/runtime-client.mjs', 'lib/runtime-host.mjs', 'lib/security.mjs', 'lib/execution-adapter.mjs']) {
    requireInput(sha(await readFile(resolve(artifactRoot, 'runtime', file))) === proof.files[`conf/cyf-agent-runtime-v1/${file}`], 'RUNTIME_SOURCE_BINDING_REQUIRED');
  }
  await validateExecutionPayload(resolve(artifactRoot, 'codex-ws-agent'), { dependencies: true, toolchain: false });
  return { sourceCommit: SOURCE.commit, sourceTree: SOURCE.tree, archiveSha256: SOURCE.tarSha256,
    sourceManifestSha256: sha(await readFile(resolve(sourceRoot, 'ur04-client-source.json'))),
    artifactManifestSha256: sha(await readFile(resolve(artifactRoot, 'ur04-clean-artifact.json'))), cleanRun: artifact.cleanRun };
}
const emit = data => process.stdout.write(PREFIX + JSON.stringify(data) + '\n');
// Original default fetch is never replaced. All secret-bearing input/output stays on
// parent-owned private pipes. Public projections include booleans/counts, never digests.
const enrollmentCodes = new Set(['RUNTIME_ENROLLMENT_RECOVERY_REQUIRED', 'RUNTIME_ENROLLMENT_AUTHORIZATION_EXISTS']);
async function prepare(input) {
  const provenance = await verified(input);
  const root = await canonical(input.root);
  const codexBin = await canonical(input.codexBin, false);
  requireInput((await lstat(codexBin)).mode & 0o111, "REAL_CODEX_EXECUTABLE_REQUIRED");
  const { digestManifest } = await import(pathToFileURL(resolve(input.artifactRoot, 'runtime/lib/manifest.mjs')));
  const manifests = [];
  await mkdir(resolve(root, 'host'), { mode: 0o700 });
  const entries = [];
  for (let index = 0; index < 8; index++) {
    const base = resolve(root, `agent-${index}`); await mkdir(base, { mode: 0o700 });
    for (const dir of ['state', 'home', 'work', 'workspace', 'repository']) await mkdir(resolve(base, dir), { mode: 0o700 });
    const manifest = { runtimeProtocolVersion: 'v1', manifestVersion: '1', installationId: `rti_${(index + 1).toString(16).repeat(32)}`,
      tenantId: '0', clientId: 'ur06-client', canonicalAgentId: `agt_${(index + 1).toString(16).repeat(32)}` };
    manifest.manifestSha256 = digestManifest(manifest); manifests.push(manifest);
    // Original engine probes the genuine supplied Codex CLI. Never substitute a
    // synthetic --version receipt. No command dispatch/model call occurs in M3.
    const profile = { profileId: `ur06-${index}`, agentId: manifest.canonicalAgentId, name: 'UR06 private fixture',
      codexBin, codexHome: resolve(base, 'home'), codexWorkdir: resolve(base, 'work'),
      workspacePolicyId: `ur06-policy-${index}`, workspaceRole: 'coder', appServerEnabled: false, fastChatEnabled: false,
      codexSessionMode: 'new', codexApproval: 'never', codexSandbox: 'workspace-write', typedInspectionProviderNetwork: 'isolated' };
    const env = { PATH: process.env.PATH, HOME: profile.codexHome, LANG: 'C.UTF-8' };
    const git = args => execFileSync('/usr/bin/git', args, { cwd: resolve(base, 'repository'), env, stdio: 'pipe' });
    git(['init', '-b', 'main']); git(['config', 'user.name', 'UR06 synthetic']); git(['config', 'user.email', 'ur06@invalid']);
    await writeFile(resolve(base, 'repository/README.md'), 'UR06 private synthetic ACK fixture; no model execution\n', { mode: 0o600 });
    git(['add', 'README.md']); git(['commit', '-m', 'synthetic fixture']);
    await privateJson(resolve(base, 'manifest.json'), manifest); await privateJson(resolve(base, 'profile.json'), profile);
    if (index < 2) entries.push({ manifestPath: resolve(base, 'manifest.json'), profilePath: resolve(base, 'profile.json'), stateRoot: resolve(base, 'state') });
  }
  await privateJson(resolve(root, 'host.json'), { configVersion: 1, hostId: 'ur06-host', stateRoot: resolve(root, 'host'), agents: entries });
  emit({ stage: 'NODE_PREPARED', manifests, provenance });
}
async function verified(input) {
  const p = await verifyInputs(input);
  requireInput(process.versions.node === '20.20.2', 'NODE_20_20_2_REQUIRED');
  requireInput(sha(await readFile(process.execPath)) === sha(await readFile(resolve(input.artifactRoot, 'node/bin/node'))), 'NODE_ARTIFACT_BINARY_BINDING_REQUIRED');
  const codex = await canonical(input.codexBin, false);
  requireInput((await lstat(codex)).mode & 0o111, 'REAL_CODEX_EXECUTABLE_REQUIRED');
  return { ...p, nodeSha256: sha(await readFile(process.execPath)), codexSha256: sha(await readFile(codex)) };
}
async function privateClient(input, request) {
  requireInput(Number.isInteger(request.agentIndex) && request.agentIndex >= 0 && request.agentIndex < 8, 'EXACT_AGENT_INDEX_REQUIRED');
  const root = await canonical(input.root), base = resolve(root, `agent-${request.agentIndex}`);
  let manifest = await json(resolve(base, 'manifest.json'));
  if (request.variant === 'MANIFEST') {
    const { digestManifest } = await import(pathToFileURL(resolve(input.artifactRoot, 'runtime/lib/manifest.mjs')));
    manifest = { ...manifest, manifestVersion: '2' }; manifest.manifestSha256 = digestManifest(manifest);
  } else if (request.variant === 'SUBJECT') {
    const { digestManifest } = await import(pathToFileURL(resolve(input.artifactRoot, 'runtime/lib/manifest.mjs')));
    manifest = { ...manifest, canonicalAgentId: `agt_${'e'.repeat(32)}` }; manifest.manifestSha256 = digestManifest(manifest);
  }
  const lane = request.stateName || 'state'; requireInput(/^(state|race-[123])$/.test(lane), 'PRIVATE_STATE_NAME_REQUIRED');
  const stateDir = resolve(base, lane);
  if (lane !== 'state') { try { await mkdir(stateDir, { mode: 0o700 }); } catch (e) { if (e.code !== 'EEXIST') throw e; } }
  await canonical(stateDir); requireInput((await lstat(stateDir)).mode % 512 === 0o700, 'PRIVATE_STATE_MODE_REQUIRED');
  const { RuntimeV1Client } = await import(pathToFileURL(resolve(input.artifactRoot, 'runtime/lib/runtime-client.mjs')));
  return new RuntimeV1Client({ manifest, apiBaseUrl: input.apiOrigin, stateDir, hostId: 'ur06-host', runtimeInstanceId: `ur06-enroll-${randomUUID()}` });
}
async function enroll(input, request) {
  const client = await privateClient(input, request);
  let success = false, code = 'NONE', publicIdentityOnly = false;
  try {
    const result = await client.enroll(request.secret); success = true;
    publicIdentityOnly = Object.keys(result).join(',') === 'installationId' && result.installationId === client.manifest.installationId;
  } catch (error) { code = enrollmentCodes.has(error?.code) ? error.code : 'OTHER'; }
  const state = await credentialState(client);
  emit({ stage: 'ENROLLED', success, code, publicIdentityOnly, ...state });
}
async function credentialState(client) {
  const files = await readdir(client.stateDir); const marker = await json(client.enrollmentAttemptPath());
  let authorizationExists = false, privateMode = false;
  try {
    const st = await lstat(client.authorizationPath()); requireInput(!st.isSymbolicLink() && st.isFile(), 'PATH_ALIAS_FORBIDDEN');
    privateMode = (st.mode & 0o777) === 0o600; await client.loadAuthorization(); authorizationExists = true;
  } catch (error) { if (error.code !== 'ENOENT') throw error; }
  const markerBytes = JSON.stringify(marker);
  return { authorizationExists, privateMode, noAliases: (await Promise.all(files.map(f => lstat(resolve(client.stateDir, f))))).every(st => !st.isSymbolicLink()),
    markerPublicOnly: !/rta1_|rts1_|enrollmentSecret|authorization/i.test(markerBytes),
    markerAttempted: marker.status === 'REQUEST_MAY_CONSUME_SECRET' };
}
async function host(input, lines) {
  const root = await canonical(input.root);
  const { readRuntimeHostConfig } = await import(pathToFileURL(resolve(input.artifactRoot, 'runtime/lib/manifest.mjs')));
  const { runUnifiedRuntime } = await import(pathToFileURL(resolve(input.artifactRoot, 'runtime/agent-runtime.mjs')));
  const { createExecutionAdapterFactory } = await import(pathToFileURL(resolve(input.artifactRoot, 'runtime/lib/execution-adapter.mjs')));
  const config = await readRuntimeHostConfig(resolve(root, 'host.json'));
  const controller = new AbortController(), executors = new Map(), isolated = new Set(); let failed = false;
  // Observation only: no override of client/socket/engine/registration/ready methods.
  const observing = async options => {
    const original = await createExecutionAdapterFactory(options);
    return { ...original, createExecutor(settings) { const actual = original.createExecutor(settings); executors.set(settings.subjectKey, actual); return actual; } };
  };
  const workspacePolicies = new Map(config.agents.map((a, i) => [a.profile.workspacePolicyId, { policyId: a.profile.workspacePolicyId,
    root: resolve(root, `agent-${i}/workspace`), repository: resolve(root, `agent-${i}/repository`), baseRef: 'refs/heads/main',
    trustedRemoteUrl: 'https://ur06.invalid/synthetic.git', trustedRemoteRef: 'refs/heads/main' }]));
  const running = runUnifiedRuntime({ config, apiOrigin: input.apiOrigin, instanceId: `ur06-boot-${randomUUID()}`, signal: controller.signal,
    createAdapters: observing, workspacePolicies, logger: (event, fields) => { if (event === 'runtime-agent-isolated') isolated.add(fields.subjectKey); } })
    .catch(() => { failed = true; emit({ stage: 'ERROR', code: 'ORIGINAL_RUNTIME_FAILURE' }); });
  const old = new Map();
  const stateFor = i => { requireInput(Number.isInteger(i) && i >= 0 && i < 2, 'EXACT_AGENT_INDEX_REQUIRED'); return executors.get(config.agents[i].subjectKey)?.state(); };
  const recoveryPath = '/internal/agent/tasks/ur06-missing/work-items/ur06-missing/reassignments/ur06-missing/commands/ur06-missing/result-commit';
  const consumeResponse = async response => { await response.arrayBuffer(); return response.status; };
  emit({ stage: 'HOST_BOOT', pid: process.pid });
  try {
    for await (const line of lines) {
      const r = JSON.parse(line);
      if (r.op === 'STOP') { controller.abort(); await running; await verified(input); emit({ stage: 'NODE_STOPPED' }); return; }
      if (r.op === 'SNAPSHOT') {
        const agents = config.agents.map((a, i) => {
          const e = executors.get(a.subjectKey), s = e?.state(), t = s?.runtimeTransport;
          const h = t?.reportReady() ? t.headers() : null;
          return { ready: e?.ready() === true, registered: t?.reportReady() === true, socketOpen: s?.ws?.readyState === 1,
            types: e?.readyCommandTypes() || [], generation: h ? Number(h['X-Agent-Session-Generation']) : 0, isolated: isolated.has(a.subjectKey) };
        });
        emit({ stage: 'NODE_SNAPSHOT', agents, failed }); continue;
      }
      const s = stateFor(r.agentIndex);
      if (!['STALE_NATIVE', 'STALE_ACK', 'DISCONNECT'].includes(r.op)) requireInput(s?.runtimeTransport?.reportReady(), 'CURRENT_TRANSPORT_REQUIRED');
      const t = s.runtimeTransport;
      if (r.op === 'CAPTURE_PROOF') {
        old.set(r.agentIndex, t.headers()); emit({ stage: 'PROOF_CAPTURED', generation: Number(old.get(r.agentIndex)['X-Agent-Session-Generation']) });
      } else if (r.op === 'PRIVATE_PROOF') {
        // Private pipe only, retained by parent in memory across A/B restarts.
        emit({ stage: 'PRIVATE_PROOF', headers: t.headers() });
      } else if (r.op === 'STALE_NATIVE') {
        const response = await globalThis.fetch(new URL(recoveryPath, input.apiOrigin), { headers: r.headers || old.get(r.agentIndex), redirect: 'error' });
        emit({ stage: 'NATIVE_RESULT', status: await consumeResponse(response) });
      } else if (r.op === 'NATIVE') {
        emit({ stage: 'NATIVE_RESULT', status: await consumeResponse(await t.nativeFetch(new URL(recoveryPath, input.apiOrigin))) });
      } else if (r.op === 'ACK' || r.op === 'STALE_ACK') {
        const manifest = config.agents[r.agentIndex].manifest;
        const command = { ...r.command, installationId: manifest.installationId, tenantId: manifest.tenantId, clientId: manifest.clientId,
          canonicalAgentId: manifest.canonicalAgentId, payloadReference: null };
        if (r.op === 'ACK') {
          try { const receipt = await t.acknowledge(command, r.status, r.deliveryVersion ?? null); emit({ stage: 'ACK_RESULT', success: true, ...receipt }); }
          catch (e) { emit({ stage: 'ACK_RESULT', success: false, httpStatus: [400,401,403,404,409,500,503].includes(e.status) ? e.status : 0 }); }
        } else {
          const h = r.headers || old.get(r.agentIndex); requireInput(h, 'CURRENT_TRANSPORT_REQUIRED');
          const response = await globalThis.fetch(new URL(`/agent/runtime/v1/commands/${command.messageId}/acks`, input.apiOrigin), {
            method: 'POST', redirect: 'error', headers: { ...h, 'Content-Type': 'application/json' }, body: JSON.stringify({ ...command,
              hostId: h['X-Agent-Host-Id'], runtimeInstanceId: h['X-Agent-Runtime-Id'], sessionGeneration: Number(h['X-Agent-Session-Generation']),
              status: r.status, deliveryVersion: r.deliveryVersion ?? null }) });
          emit({ stage: 'ACK_RESULT', success: false, httpStatus: await consumeResponse(response) });
        }
      } else if (r.op === 'ROTATE') {
        const { RuntimeV1Client } = await import(pathToFileURL(resolve(input.artifactRoot, 'runtime/lib/runtime-client.mjs')));
        const a = config.agents[r.agentIndex];
        const c = new RuntimeV1Client({ manifest: a.manifest, apiBaseUrl: input.apiOrigin, stateDir: a.stateRoot,
          hostId: config.hostId, runtimeInstanceId: `ur06-rotate-${randomUUID()}` });
        const session = await c.session(); emit({ stage: 'ROTATED', generation: session.sessionGeneration });
      } else if (r.op === 'DISCONNECT') { requireInput(s?.ws?.readyState === 1, 'OWNED_SUBJECT_SOCKET_REQUIRED'); s.ws.close(1000); emit({ stage: 'DISCONNECTED' }); }
      else throw fail('UNKNOWN_PRIVATE_OPERATION');
    }
    throw fail('EXPLICIT_STOP_REQUIRED');
  } finally { controller.abort(); await running; }
}
async function main() {
  requireInput(process.env.UR06_PRIVATE_CHILD === '1', 'PRIVATE_CHILD_ONLY');
  for (const key of ['log', 'warn', 'error', 'info', 'debug']) console[key] = () => {};
  const lines = createInterface({ input: process.stdin, crlfDelay: Infinity })[Symbol.asyncIterator]();
  const first = await lines.next(); requireInput(!first.done, 'PRIVATE_INIT_REQUIRED'); const input = JSON.parse(first.value);
  if (input.op === 'PREPARE') { await prepare(input); return; }
  const provenance = await verified(input);
  if (input.op === 'VERIFY') { emit({ stage: 'VERIFIED', provenance }); return; }
  requireInput(new URL(input.apiOrigin).hostname === '127.0.0.1' && new URL(input.apiOrigin).port !== '', 'PRIVATE_ORIGIN_REQUIRED');
  if (input.op === 'ENROLL') {
    if (input.pauseBeforeRequest === true) {
      emit({ stage: 'ENROLL_READY' });
      const start = await lines.next(); requireInput(!start.done && JSON.parse(start.value).op === 'START', 'PRIVATE_ENROLL_START_REQUIRED');
    }
    await enroll(input, input); await verified(input); return;
  }
  if (input.op === 'WS_PROBE') {
    const { loadWebSocketClient } = await import(pathToFileURL(resolve(input.artifactRoot, 'codex-ws-agent/agent-client.mjs')));
    const Socket = await loadWebSocketClient();
    const url = new URL('/ws/agent/channel', input.apiOrigin); url.protocol = 'ws:';
    const socket = new Socket(url, { headers: input.headers, followRedirects: false });
    const status = await new Promise(resolveStatus => {
      socket.on('unexpected-response', (_r, response) => { response.resume(); socket.terminate(); resolveStatus(response.statusCode); });
      socket.on('open', () => { socket.close(1000); resolveStatus(200); });
      socket.on('error', () => {}); // actual HTTP/transport receipt or close is authoritative
      socket.on('close', () => resolveStatus(0));
    });
    await new Promise(done => { if (socket.readyState === 3) done(); else socket.once('close', done); });
    emit({ stage: 'WS_PROBE', status }); return;
  }
  requireInput(input.op === 'HOST', 'PRIVATE_INIT_REQUIRED'); await host(input, { [Symbol.asyncIterator]: () => lines });
}
if (process.argv[1] && fileURLToPath(import.meta.url) === resolve(process.argv[1])) main().catch(error => {
  emit({ stage: 'ERROR', code: 'UR06_NODE_FAILURE', at: phase, failureCode: SAFE_CODES.has(error?.code) ? error.code : 'OTHER' }); process.exitCode = 1;
});
