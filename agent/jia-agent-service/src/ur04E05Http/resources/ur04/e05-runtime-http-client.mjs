#!/usr/bin/env node
// UR04 private pipe client: default real fetch/ws, original host/adapter/engine.
// No fake server/client/socket/executor/lease/result. External clean artifact required.
import assert from 'node:assert/strict';
import { createHash, randomUUID } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { createInterface } from 'node:readline';
import { chmod, lstat, mkdir, readFile, readdir, readlink, realpath, writeFile } from 'node:fs/promises';
import { isAbsolute, resolve } from 'node:path';
import { pathToFileURL, fileURLToPath } from 'node:url';
export const SOURCE = Object.freeze({ commit: '5666fd2c990e4577cd4e16d5a32924ea0f3a8b7b',
  tree: 'd3cdc69da62bddc48af0bc86b4f32dbfa9429b60', count: 285,
  tarSha256: '9b5d74b27a91579359ad0a18d241f66f19d6df13d376e3cff5dbd45b729b7687', tarBytes: 3635200 });
const PREFIX = 'UR04_PIPE ';
let phase = 'PRIVATE_INPUT';
const SAFE_CODES = new Set(['ARTIFACT_ALIAS_FORBIDDEN', 'ARTIFACT_FULL_INVENTORY_REQUIRED', 'ARTIFACT_LINK_PROOF_REQUIRED', 'ARTIFACT_MEMBER_MISMATCH', 'CHILD_INTERPRETER_PATH_UNSAFE', 'CLEAN_ARTIFACT_EVIDENCE_REQUIRED', 'CLIENT_COMMIT_REQUIRED', 'PRIVATE_CHILD_ONLY', 'CURRENT_TRANSPORT_REQUIRED', 'ENGINE_SOURCE_BINDING_REQUIRED', 'EXACT_AGENT_INDEX_REQUIRED', 'EXPLICIT_STOP_REQUIRED', 'MEMBER_UNSAFE', 'NEGATIVE_ALLOWLIST_REQUIRED', 'NEGATIVE_COMMAND_BINDING_REQUIRED', 'NODE_20_20_2_REQUIRED', 'NODE_ARTIFACT_BINARY_BINDING_REQUIRED', 'ORIGINAL_CONFLICT_AUDIT_REQUIRED', 'ONE_ORIGINAL_CHECKPOINT_REQUIRED', 'ONE_ORIGINAL_MATERIAL_REQUIRED', 'ORIGINAL_MATERIAL_REQUIRED', 'OWNED_SUBJECT_SOCKET_REQUIRED', 'PATH_ALIAS_FORBIDDEN', 'PATH_CANONICAL_REQUIRED', 'PATH_TYPE_REQUIRED', 'PIPE_REQUEST_REQUIRED', 'PRIOR_TERMINAL_REQUIRED', 'PRIVATE_INIT_REQUIRED', 'PRIVATE_THREE_SUBJECTS_REQUIRED', 'REAL_LEASE_FOR_NEGATIVE_REQUIRED', 'RUNTIME_SOURCE_BINDING_REQUIRED', 'SOURCE_ALIAS_FORBIDDEN', 'SOURCE_ARCHIVE_MISMATCH', 'SOURCE_FULL_TREE_MISMATCH', 'SOURCE_IDENTITY_REQUIRED', 'SOURCE_MEMBER_HASH_REQUIRED', 'SOURCE_MEMBER_MISMATCH', 'SOURCE_UNEXPECTED_MEMBER', 'SYNTHETIC_AUTH_REQUIRED', 'TRACKED_SYNTHETIC_MODULE_REQUIRED', 'UNKNOWN_PRIVATE_OPERATION']);
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
const delay = ms => new Promise(r => setTimeout(r, ms));
async function prepare(input) {
  await verifyInputs(input); requireInput(process.versions.node === '20.20.2', 'NODE_20_20_2_REQUIRED');
  requireInput(sha(await readFile(process.execPath)) === sha(await readFile(resolve(input.artifactRoot, 'node/bin/node'))), 'NODE_ARTIFACT_BINARY_BINDING_REQUIRED');
  const root = await canonical(input.root); const { digestManifest } = await import(pathToFileURL(resolve(input.artifactRoot, 'runtime/lib/manifest.mjs')));
  phase = 'PRIVATE_SUBJECT_PREPARE';
  await mkdir(resolve(root, 'host'), { mode: 0o700 });
  const entries = [], manifests = [];
  for (let index = 0; index < 3; index++) {
    const base = resolve(root, `agent-${index}`);
    await mkdir(base, { mode: 0o700 });
    for (const dir of ['state', 'home', 'work', 'workspace', 'repository']) await mkdir(resolve(base, dir), { mode: 0o700 });
    const manifest = { runtimeProtocolVersion: 'v1', manifestVersion: '1', installationId: `rti_${String(index + 1).repeat(32)}`,
      tenantId: '0', clientId: 'ur04-client', canonicalAgentId: `agt_${'abc'[index].repeat(32)}` };
    manifest.manifestSha256 = digestManifest(manifest); manifests.push(manifest);
    const childPath = resolve(base, 'synthetic-executor.mjs');
    const node = await canonical(input.nodeBin, false);
    requireInput(!/\s/.test(node), 'CHILD_INTERPRETER_PATH_UNSAFE');
    const childSource = await readFile(input.childModule, 'utf8');
    requireInput(childSource.startsWith('#!/usr/bin/env node\n'), 'TRACKED_SYNTHETIC_MODULE_REQUIRED');
    await writeFile(childPath, childSource.replace(/^#![^\n]+/, `#!${node}`), { mode: 0o700 });
    // Direct pinned interpreter, no shell/PWD/SHLVL additions or env fallback.
    const profile = { profileId: `ur04-${index}`, agentId: manifest.canonicalAgentId, name: 'UR04 SYNTHETIC',
      codexBin: childPath, codexHome: resolve(base, 'home'), codexWorkdir: resolve(base, 'work'),
      workspacePolicyId: `ur04-policy-${index}`, workspaceRole: 'coder', appServerEnabled: false, fastChatEnabled: false,
      codexSessionMode: 'new', codexApproval: 'never', codexSandbox: 'workspace-write', typedInspectionProviderNetwork: 'isolated' };
    const environment = { PATH: process.env.PATH, HOME: resolve(base, 'home'), LANG: 'C.UTF-8' };
    const git = args => execFileSync('git', args, { cwd: resolve(base, 'repository'), env: environment, stdio: 'pipe' });
    git(['init', '-b', 'main']); git(['config', 'user.name', 'UR04 synthetic']); git(['config', 'user.email', 'ur04@invalid']);
    await writeFile(resolve(base, 'repository/README.md'), 'UR04 SYNTHETIC no Provider\n', { mode: 0o600 });
    git(['add', 'README.md']); git(['commit', '-m', 'synthetic workspace']);
    await privateJson(resolve(base, 'home/ur04-child-control.json'), { workspaceRoot: resolve(base, 'workspace') });
    await privateJson(resolve(base, 'manifest.json'), manifest); await privateJson(resolve(base, 'profile.json'), profile);
    entries.push({ manifestPath: resolve(base, 'manifest.json'), profilePath: resolve(base, 'profile.json'), stateRoot: resolve(base, 'state') });
  }
  await privateJson(resolve(root, 'host.json'), { configVersion: 1, hostId: 'ur04-host', stateRoot: resolve(root, 'host'), agents: entries });
  emit({ stage: 'NODE_PREPARED', manifests });
}
async function runtime(input, lines) {
  const provenance = await verifyInputs(input); requireInput(process.versions.node === '20.20.2', 'NODE_20_20_2_REQUIRED');
  requireInput(sha(await readFile(process.execPath)) === sha(await readFile(resolve(input.artifactRoot, 'node/bin/node'))), 'NODE_ARTIFACT_BINARY_BINDING_REQUIRED');
  const root = await canonical(input.root);
  const { readRuntimeHostConfig } = await import(pathToFileURL(resolve(input.artifactRoot, 'runtime/lib/manifest.mjs')));
  const { runUnifiedRuntime } = await import(pathToFileURL(resolve(input.artifactRoot, 'runtime/agent-runtime.mjs')));
  const { createExecutionAdapterFactory } = await import(pathToFileURL(resolve(input.artifactRoot, 'runtime/lib/execution-adapter.mjs')));
  phase = 'ORIGINAL_HOST_CONFIG';
  const config = await readRuntimeHostConfig(resolve(root, 'host.json'));
  requireInput(new URL(input.apiOrigin).hostname === '127.0.0.1' && config.agents.length === 3, 'PRIVATE_THREE_SUBJECTS_REQUIRED');
  if (input.authorizations) for (let i = 0; i < config.agents.length; i++) {
    requireInput(/^rta1_[0-9a-f]{64}$/.test(input.authorizations[i]), 'SYNTHETIC_AUTH_REQUIRED');
    await privateJson(resolve(config.agents[i].stateRoot, 'runtime-authorization.json'), { installationId: config.agents[i].manifest.installationId, runtimeAuthorization: input.authorizations[i] });
  }
  const workspacePolicies = new Map(config.agents.map((agent, index) => [agent.profile.workspacePolicyId, {
    policyId: agent.profile.workspacePolicyId, root: resolve(root, `agent-${index}/workspace`), repository: resolve(root, `agent-${index}/repository`),
    baseRef: 'refs/heads/main', trustedRemoteUrl: 'https://ur04.invalid/synthetic.git', trustedRemoteRef: 'refs/heads/main' }]));
  const controller = new AbortController(), executors = new Map(), isolated = new Set(); let runtimeFailed = false;
  // Transparent observer only: original default factory and every original executor method unchanged.
  const observeFactory = async options => {
    const actual = await createExecutionAdapterFactory(options);
    return { ...actual, createExecutor: settings => { const executor = actual.createExecutor(settings); executors.set(settings.subjectKey, executor); return executor; } };
  };
  phase = 'ORIGINAL_HOST_RUNNING';
  const running = runUnifiedRuntime({ config, apiOrigin: input.apiOrigin, instanceId: `ur04-boot-${randomUUID()}`,
    signal: controller.signal, createAdapters: observeFactory, workspacePolicies, heartbeatIntervalMs: 100,
    logger: (event, fields) => { if (event === 'runtime-agent-isolated' && fields?.subjectKey) isolated.add(fields.subjectKey); } }).catch(() => { runtimeFailed = true; emit({ stage: 'ERROR', code: 'ORIGINAL_RUNTIME_FAILURE' }); });
  emit({ stage: 'NODE_BOOT', pid: process.pid, ...provenance, nodeVersion: process.versions.node, nodeSha256: sha(await readFile(process.execPath)), synthetic: true });
  async function snapshot() {
    const agents = [];
    for (const agent of config.agents) {
      const executor = executors.get(agent.subjectKey), state = executor?.state();
      let ledger = [], inbox = [], pending = [], confirmed = [], conflicts = [];
      if (state) {
        ledger = state.ledger.listEntries().map(e => ({ commandId: e.commandId, status: e.status, fingerprint: e.fingerprint }));
        // Read the actual original dedupe conflict audit, never fabricate an ingress completion marker.
        for (const file of await readdir(state.ledger.conflictsDir)) {
          requireInput(/^[A-Za-z0-9-]+\.json$/.test(file), 'ORIGINAL_CONFLICT_AUDIT_REQUIRED');
          const path = resolve(state.ledger.conflictsDir, file);
          requireInput(!(await lstat(path)).isSymbolicLink() && (await lstat(path)).isFile(), 'ORIGINAL_CONFLICT_AUDIT_REQUIRED');
          const conflict = await json(path);
          requireInput(typeof conflict.commandId === 'string' && /^[0-9a-f]{64}$/.test(conflict.existingFingerprint)
            && /^[0-9a-f]{64}$/.test(conflict.conflictingFingerprint) && conflict.existingFingerprint !== conflict.conflictingFingerprint,
            'ORIGINAL_CONFLICT_AUDIT_REQUIRED');
          conflicts.push({ commandId: conflict.commandId, existingFingerprint: conflict.existingFingerprint, conflictingFingerprint: conflict.conflictingFingerprint });
        }
        inbox = [...state.inbox.commandStateIndex().values()].flat().map(e => ({ commandId: e.normalized.commandId,
          state: e.record.state, fingerprint: e.record.fingerprint ?? null, materialDigest: e.record.e05ResultDigest ?? null }));
        pending = state.ackOutbox.pendingEnvelopes().map(e => ({ commandId: e.envelope.commandId, status: e.envelope.ackStatus, sequence: e.record.queueSequence }));
        confirmed = [...state.inbox.commandStateIndex().values()].flat().map(e => ({ commandId: e.normalized.commandId,
          commit: state.ledger.runtimeAckCommit(e.normalized.commandId, e.normalized.messageId) ?? null }));
      }
      const childFile = resolve(agent.profile.codexHome, 'ur04-child.jsonl'); let children = [];
      try { children = (await readFile(childFile, 'utf8')).trim().split('\n').filter(Boolean).map(JSON.parse); } catch (e) { if (e.code !== 'ENOENT') throw e; }
      const records = state ? [...state.inbox.commandStateIndex().values()].flat().map(e => e.record) : [];
      const checkpointBytes = JSON.stringify({ records, ledger: state?.ledger.listEntries() || [], pending: state?.ackOutbox.pendingEnvelopes() || [] });
      const credentialsAbsent = !/rts1_[0-9a-f]{64}|rta1_[0-9a-f]{64}|"leaseToken"|"sessionToken"/.test(checkpointBytes);
      agents.push({ credentialsAbsent, isolated: isolated.has(agent.subjectKey), ready: executor?.ready() === true, subjectKey: agent.subjectKey, ledger, inbox, pending, confirmed, conflicts, children });
    }
    return { stage: 'NODE_SNAPSHOT', agents, pid: process.pid, runtimeFailed };
  }
  try {
    for await (const line of lines) {
      const request = JSON.parse(line); requireInput(object(request), 'PIPE_REQUEST_REQUIRED');
      if (request.op === 'STOP') { controller.abort(); await running; emit({ stage: 'NODE_STOPPED' }); return; }
      if (request.op === 'SNAPSHOT') { emit(await snapshot()); continue; }
      requireInput(Number.isInteger(request.agentIndex) && request.agentIndex >= 0 && request.agentIndex < 3, 'EXACT_AGENT_INDEX_REQUIRED');
      const agent = config.agents[request.agentIndex], executor = executors.get(agent.subjectKey), state = executor?.state();
      if (request.op === 'DISCONNECT') {
        requireInput(state?.ws?.readyState === 1, 'OWNED_SUBJECT_SOCKET_REQUIRED');
        state.ws.close(1000); // genuine owned native channel close, original adapter reconnect/auth path
        emit({ stage: 'SUBJECT_DISCONNECTED' });
      } else if (request.op === 'RELEASE') {
        await writeFile(resolve(agent.profile.codexHome, 'ur04-release'), 'heartbeat-confirmed\n', { mode: 0o600 }); emit({ stage: 'CHILD_RELEASED' });
      } else if (request.op === 'ROTATE') {
        const { RuntimeV1Client } = await import(pathToFileURL(resolve(input.artifactRoot, 'runtime/lib/runtime-client.mjs')));
        const rotation = new RuntimeV1Client({ manifest: agent.manifest, apiBaseUrl: input.apiOrigin, stateDir: agent.stateRoot,
          hostId: config.hostId, runtimeInstanceId: `ur04-rotation-${randomUUID()}` });
        const session = await rotation.session();
        emit({ stage: 'ROTATED', generation: session.sessionGeneration });
      } else if (request.op === 'NEGATIVE_HTTP') {
        requireInput(state?.runtimeTransport && ['ACTOR','SCOPE','OLD_SESSION','UNREGISTERED','PRODUCER'].includes(request.variant), 'NEGATIVE_ALLOWLIST_REQUIRED');
        // Genuine HTTP negative requests intentionally bypass client URI rejection, never server auth.
        const command = request.command;
        requireInput(object(command) && /^ur04-task-[0-2]$/.test(command.taskId) && /^ur04-work-[0-2]$/.test(command.workItemId)
          && /^rsn_[0-9a-f]{64}$/.test(command.reassignmentId) && /^cmd_hall_action_[0-9a-f]{64}$/.test(command.commandId), 'NEGATIVE_COMMAND_BINDING_REQUIRED');
        let path = `/internal/agent/tasks/${command.taskId}/work-items/${command.workItemId}/reassignments/${command.reassignmentId}/commands/${command.commandId}/result-commit`;
        let headers = state.runtimeTransport.headers(), method = 'GET', body;
        if (request.variant === 'ACTOR') {
          path = `/agent/tasks/${command.taskId}/work-items/${command.workItemId}/reassignments/${command.reassignmentId}/lease?actorAgentId=${config.agents[(request.agentIndex + 1) % 3].manifest.canonicalAgentId}`;
          method = 'POST'; body = { commandId: command.commandId, expectedWorkItemVersion: 5 };
        } else if (request.variant === 'SCOPE') headers = { ...headers, 'X-Agent-Id': config.agents[(request.agentIndex + 1) % 3].manifest.canonicalAgentId };
        else if (request.variant === 'OLD_SESSION' || request.variant === 'UNREGISTERED') {
          const { RuntimeV1Client } = await import(pathToFileURL(resolve(input.artifactRoot, 'runtime/lib/runtime-client.mjs')));
          const pending = new RuntimeV1Client({ manifest: agent.manifest, apiBaseUrl: input.apiOrigin, stateDir: agent.stateRoot, hostId: config.hostId, runtimeInstanceId: `ur04-negative-${randomUUID()}` });
          await pending.session(); if (request.variant === 'UNREGISTERED') headers = pending.sessionHeaders();
        } else if (request.variant === 'PRODUCER') {
          const records = state.inbox.commandStateIndex().get(command.commandId);
          requireInput(records?.length === 1 && records[0].record.e05ResultMaterial, 'ORIGINAL_MATERIAL_REQUIRED');
          const material = structuredClone(records[0].record.e05ResultMaterial);
          const leasePath = path.replace(/result-commit$/, 'lease');
          const current = await state.runtimeTransport.nativeFetch(new URL(leasePath, input.apiOrigin), { method: 'GET' });
          requireInput(current.status === 200, 'REAL_LEASE_FOR_NEGATIVE_REQUIRED'); const lease = await current.json();
          material.producerAgentId = config.agents[(request.agentIndex + 1) % 3].manifest.canonicalAgentId;
          method = 'POST'; body = { ...material, leaseToken: lease.leaseToken }; // genuine same current lease, wrong producer
        }
        const response = await globalThis.fetch(new URL(path, input.apiOrigin), { method, headers: { ...headers, 'Content-Type': 'application/json' },
          redirect: 'error', ...(body ? { body: JSON.stringify(body) } : {}) });
        await response.arrayBuffer(); emit({ stage: 'NEGATIVE_HTTP', status: response.status });
      } else if (request.op === 'HTTP') {
        requireInput(state?.runtimeTransport && typeof request.path === 'string' && request.path.startsWith('/'), 'CURRENT_TRANSPORT_REQUIRED');
        // Real nativeFetch applies current transport proof and exact production path ACL.
        const response = await state.runtimeTransport.nativeFetch(new URL(request.path, input.apiOrigin), { method: request.method,
          headers: { 'Content-Type': 'application/json' }, ...(request.body ? { body: JSON.stringify(request.body) } : {}) });
        const bytes = Buffer.from(await response.arrayBuffer());
        emit({ stage: 'HTTP_RESULT', status: response.status, bodySha256: sha(bytes) });
      } else if (request.op === 'ORIGINAL_READBACK') {
        requireInput(state?.runtimeTransport, 'CURRENT_TRANSPORT_REQUIRED');
        const records = state.inbox.commandStateIndex().get(request.commandId);
        requireInput(records?.length === 1 && records[0].record.e05ResultMaterial, 'ONE_ORIGINAL_MATERIAL_REQUIRED');
        const { recoverE05Result } = await import(pathToFileURL(resolve(input.artifactRoot, 'codex-ws-agent/agent-client.mjs')));
        const outcome = await recoverE05Result({ profile: state.profile, message: records[0].normalized, record: records[0].record,
          nativeFetch: state.runtimeTransport.nativeFetch, apiOrigin: input.apiOrigin });
        emit({ stage: 'ORIGINAL_READBACK', completed: outcome.status === 'completed', materialDigest: records[0].record.e05ResultDigest });
      } else if (request.op === 'RECOVER') {
        requireInput(state?.runtimeTransport, 'CURRENT_TRANSPORT_REQUIRED');
        await state.processor.reconcileE05Results({ nativeFetch: state.runtimeTransport.nativeFetch, apiOrigin: input.apiOrigin });
        emit({ stage: 'RECOVERY_OBSERVED' });
      } else if (request.op === 'DUPLICATE_ACK') {
        requireInput(state, 'CURRENT_TRANSPORT_REQUIRED'); const records = state.inbox.commandStateIndex().get(request.commandId);
        requireInput(records?.length === 1, 'ONE_ORIGINAL_CHECKPOINT_REQUIRED');
        const record = records[0]; const { runtimeCommandContext } = await import(pathToFileURL(resolve(input.artifactRoot, 'codex-ws-agent/agent-client.mjs')));
        const previous = state.ledger.runtimeAckCommit(request.commandId, record.normalized.messageId);
        requireInput(previous?.status === 'SUCCEEDED', 'PRIOR_TERMINAL_REQUIRED');
        const result = await state.runtimeTransport.acknowledge(runtimeCommandContext(state.profile, record.normalized), 'SUCCEEDED', previous.deliveryVersion);
        emit({ stage: 'DUPLICATE_ACK', kind: result.kind, status: result.status, deliveryVersion: result.deliveryVersion });
      } else throw fail('UNKNOWN_PRIVATE_OPERATION');
    }
    throw fail('EXPLICIT_STOP_REQUIRED');
  } finally { controller.abort(); await running; }
}
export function selfcheck() {
  assert.throws(() => member('../escape')); assert.throws(() => member('/absolute')); assert.throws(() => member('x/.git/config'));
  assert.equal(member('conf/codex-ws-agent/agent-client.mjs'), 'conf/codex-ws-agent/agent-client.mjs');
  assert.throws(() => validateSourceIdentity({})); assert.throws(() => validateSourceIdentity({ ...SOURCE, format: 'ur03-git-archive-v1', files: {} }));
  assert.equal(treeDigest([]), '4b825dc642cb6eb9a060e54bf8d69288fbee4904');
  // Synthetic identity only, not archive/artifact acceptance: each exact provenance
  // component must reject a mismatch independently (no secondary source accepted).
  const proof = { format: 'ur04-full-git-archive-v1', commit: SOURCE.commit, tree: SOURCE.tree,
    archiveSha256: SOURCE.tarSha256, archiveBytes: SOURCE.tarBytes,
    files: Object.fromEntries(Array.from({ length: SOURCE.count }, (_, i) => [`synthetic-${i}`, '0'.repeat(64)])) };
  validateSourceIdentity(proof);
  for (const [key, value] of [['commit', '0'.repeat(40)], ['tree', '0'.repeat(40)],
    ['archiveSha256', '0'.repeat(64)], ['archiveBytes', SOURCE.tarBytes - 1], ['files', {}]]) {
    assert.throws(() => validateSourceIdentity({ ...proof, [key]: value }), { code: 'SOURCE_IDENTITY_REQUIRED' });
  }
  process.stdout.write('UR04_STATIC_SELF_CHECK_PASS assertions=13 business=NOT_RUN\n');
}
async function main() {
  if (process.argv.includes('--selfcheck')) { selfcheck(); return; }
  if (process.argv.includes('--describe')) {
    process.stdout.write(JSON.stringify({ source: SOURCE, environments: ['UR04_API_COMMIT','UR04_CLIENT_ROOT','UR04_CLIENT_COMMIT','UR04_ARTIFACT_ROOT','UR04_NODE_BIN'],
      sourceProof: 'ur04-client-source.json {format:ur04-full-git-archive-v1,commit,tree,archivePath,archiveSha256,archiveBytes,files:{path:sha256}}',
      artifactProof: 'ur04-clean-artifact.json {format:ur04-clean-artifact-v1,sourceCommit,sourceTree,archiveSha256,cleanRun,checks:{C1:PASS,C2:PASS,C3:PASS,C4:PASS},files:{path:sha256|{type:symlink,target,sha256_of_link_text}}}',
      business: 'NOT_RUN', model: 'SYNTHETIC_EXECUTOR_NOT_PROVIDER' }) + '\n'); return;
  }
  requireInput(process.env.UR04_PRIVATE_CHILD === '1', 'PRIVATE_CHILD_ONLY');
  for (const key of ['log','warn','error','info','debug']) console[key] = () => {}; // private protocol is the only exported output
  const lines = createInterface({ input: process.stdin, crlfDelay: Infinity })[Symbol.asyncIterator]();
  const first = await lines.next(); requireInput(!first.done, 'PRIVATE_INIT_REQUIRED'); const input = JSON.parse(first.value);
  if (input.op === 'PREPARE') { await prepare(input); return; }
  requireInput(input.op === 'INIT', 'PRIVATE_INIT_REQUIRED'); await runtime(input, { [Symbol.asyncIterator]: () => lines });
}
if (process.argv[1] && fileURLToPath(import.meta.url) === resolve(process.argv[1])) main().catch(error => {
  emit({ stage: 'ERROR', code: 'UR04_NODE_FAILURE', at: phase, failureCode: SAFE_CODES.has(error?.code) ? error.code : 'OTHER' }); process.exitCode = 1;
});
