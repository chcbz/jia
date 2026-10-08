// UR-03 real paired-client driver. No server, injected fetch, WS readiness or business execution.
// Credentials cross private pipes/private state only; stdout is a sanitized control protocol.
import { createInterface } from 'node:readline';
import { realpath, mkdir } from 'node:fs/promises';
import { resolve, sep } from 'node:path';
import { pathToFileURL } from 'node:url';
import { createHash } from 'node:crypto';

const lines = createInterface({ input: process.stdin, crlfDelay: Infinity });
const send = value => process.stdout.write(`${JSON.stringify(value)}\n`);
const check = (condition, code) => { if (!condition) throw Object.assign(new Error(code), { fixtureCode: code }); };
const originOf = value => {
  const u = new URL(value);
  check(u.protocol === 'http:' && u.hostname === '127.0.0.1' && Number(u.port) > 0
    && !u.username && !u.password && u.pathname === '/' && !u.search && !u.hash, 'UR03_LOOPBACK_REQUIRED');
  return u.origin;
};
let RuntimeV1Client, oldClient, currentClient, commands, confirmed = null, firstGeneration, firstDigest;
let failedVersion, stateDir;
const originalFetch = globalThis.fetch;
async function initialize(input) {
  check(process.version === 'v20.20.2', 'UR03_NODE_VERSION_REQUIRED');
  check(/^[0-9a-f]{40}$/.test(input.clientCommit) && /^[0-9a-f]{40}$/.test(input.apiCommit), 'UR03_SOURCE_PROOF_REQUIRED');
  const clientRoot = await realpath(input.clientRoot);
  const fixtureRoot = await realpath(input.fixtureRoot);
  stateDir = await realpath(input.stateDir);
  check(stateDir.startsWith(`${fixtureRoot}${sep}`), 'UR03_PRIVATE_STATE_REQUIRED');
  const clientPath = await realpath(resolve(clientRoot, 'conf/cyf-agent-runtime-v1/lib/runtime-client.mjs'));
  const securityPath = await realpath(resolve(clientRoot, 'conf/cyf-agent-runtime-v1/lib/security.mjs'));
  check(clientPath.startsWith(`${clientRoot}${sep}`) && securityPath.startsWith(`${clientRoot}${sep}`), 'UR03_CLIENT_PATH_REQUIRED');
  ({ RuntimeV1Client } = await import(pathToFileURL(clientPath).href));
  const { writePrivateJson } = await import(pathToFileURL(securityPath).href);
  commands = input.commands;
  check(Array.isArray(commands) && commands.length === 2, 'UR03_COMMANDS_REQUIRED');
  await mkdir(stateDir, { recursive: true, mode: 0o700 });
  oldClient = new RuntimeV1Client({ manifest: input.manifest, apiBaseUrl: originOf(input.origin),
    stateDir, hostId: 'ur03-host', runtimeInstanceId: 'ur03-boot-1' });
  // Default constructor transport: both runtime and Node built-in fetch must be untouched.
  check(oldClient.fetchFn === originalFetch && globalThis.fetch === originalFetch, 'UR03_DEFAULT_FETCH_REQUIRED');
  await writePrivateJson(oldClient.authorizationPath(), { installationId: input.manifest.installationId,
    runtimeAuthorization: input.authorization });
  send({ stage: 'NODE_READY', nodeVersion: process.version });
}
async function ack(client, command, status, version, kind, expectedVersion) {
  const result = await client.acknowledge(command, status, version);
  check(result.kind === kind && result.status === status && result.deliveryVersion === expectedVersion, 'UR03_ACK_RECEIPT_MISMATCH');
  return result.deliveryVersion;
}
async function rejected(operation, status) {
  let result;
  try { await operation(); } catch (error) { result = error; }
  check(result?.code === 'RUNTIME_HTTP_REJECTED' && result.status === status, 'UR03_REAL_HTTP_REJECTION_REQUIRED');
}
async function step(input) {
  switch (input.op) {
    case 'SESSION_1': {
      const session = await oldClient.session();
      check(session.sessionGeneration === 1 && session.runtimeInstanceId === 'ur03-boot-1'
        && session.status === 'CHANNEL_PENDING', 'UR03_SESSION_1_MISMATCH');
      firstGeneration = session.sessionGeneration;
      firstDigest = createHash('sha256').update(session.sessionToken).digest('hex');
      send({ stage: input.op, generation: firstGeneration, tokenDigest: firstDigest });
      break;
    }
    case 'RECEIVED':
      confirmed = await ack(oldClient, commands[0], 'RECEIVED', null, 'ADVANCED', input.version);
      send({ stage: input.op, version: confirmed }); break;
    case 'RECEIVED_PRIOR':
      await ack(oldClient, commands[0], 'RECEIVED', confirmed, 'PRIOR', confirmed);
      send({ stage: input.op, version: confirmed }); break;
    case 'STARTED':
      confirmed = await ack(oldClient, commands[0], 'STARTED', confirmed, 'ADVANCED', input.version);
      send({ stage: input.op, version: confirmed }); break;
    case 'RESTART_PRIOR':
      oldClient.apiBaseUrl = originOf(input.origin);
      await ack(oldClient, commands[0], 'STARTED', confirmed, 'PRIOR', confirmed);
      send({ stage: input.op, version: confirmed, generation: firstGeneration }); break;
    case 'SESSION_2': {
      currentClient = new RuntimeV1Client({ manifest: oldClient.manifest, apiBaseUrl: oldClient.apiBaseUrl,
        stateDir, hostId: 'ur03-host', runtimeInstanceId: 'ur03-boot-2' });
      check(currentClient.fetchFn === originalFetch && globalThis.fetch === originalFetch, 'UR03_DEFAULT_FETCH_REQUIRED');
      const session = await currentClient.session();
      check(session.sessionGeneration === firstGeneration + 1 && session.runtimeInstanceId === 'ur03-boot-2'
        && session.status === 'CHANNEL_PENDING', 'UR03_SESSION_2_MISMATCH');
      const tokenDigest = createHash('sha256').update(session.sessionToken).digest('hex');
      check(tokenDigest !== firstDigest, 'UR03_SESSION_NOT_ROTATED');
      send({ stage: input.op, generation: session.sessionGeneration, tokenDigest }); break;
    }
    case 'STALE_REJECTED':
      // Do not invalidate G1 locally: this must reach the real server with the old token/proof.
      check(oldClient.currentSession.sessionGeneration === firstGeneration, 'UR03_OLD_PROOF_NOT_RETAINED');
      await rejected(() => oldClient.acknowledge(commands[0], 'FAILED', confirmed), 401);
      send({ stage: input.op, status: 401 }); break;
    case 'FAILED':
      failedVersion = await ack(currentClient, commands[0], 'FAILED', confirmed, 'ADVANCED', input.version);
      send({ stage: input.op, version: failedVersion }); break;
    case 'FAILED_PRIOR':
      await ack(currentClient, commands[0], 'FAILED', failedVersion, 'PRIOR', failedVersion);
      send({ stage: input.op, version: failedVersion }); break;
    case 'ROLLBACK_REJECTED':
      // Intentionally invalid last-confirmed version. Real D06 CAS runs then outer fence rolls back.
      await rejected(() => currentClient.acknowledge(commands[1], 'RECEIVED', input.version), 403);
      send({ stage: input.op, status: 403 }); break;
    case 'SECOND_RECEIVED': {
      const version = await ack(currentClient, commands[1], 'RECEIVED', null, 'ADVANCED', input.version);
      send({ stage: input.op, version }); break;
    }
    case 'STOP': oldClient.invalidateSession(); currentClient?.invalidateSession(); send({ stage: 'STOPPED' }); return false;
    default: check(false, 'UR03_UNKNOWN_STAGE');
  }
  return true;
}
let initialized = false;
try {
  for await (const line of lines) {
    const input = JSON.parse(line);
    if (!initialized) { check(input.op === 'INIT', 'UR03_INIT_REQUIRED'); await initialize(input); initialized = true; }
    else if (!await step(input)) break;
  }
  check(initialized, 'UR03_INIT_REQUIRED');
} catch (error) {
  // Never echo raw exceptions, assertion objects, URLs, payloads or credentials.
  const code = /^UR03_[A-Z0-9_]+$/.test(error?.fixtureCode ?? '') ? error.fixtureCode : 'UR03_CLIENT_FAILURE';
  const runtimeCodes = new Set(['RUNTIME_HTTP_REJECTED', 'RUNTIME_RESPONSE_INVALID', 'RUNTIME_SESSION_INVALID',
    'RUNTIME_SESSION_IDENTITY_MISMATCH', 'RUNTIME_SESSION_PROOF_INVALID', 'RUNTIME_ACK_COMMIT_UNCONFIRMED',
    'RUNTIME_ACK_SESSION_CHANGED', 'RUNTIME_COMMAND_EXPIRED', 'RUNTIME_INSTALLATION_AUTH_INVALID',
    'RUNTIME_ENROLLMENT_REQUIRED', 'RUNTIME_SESSION_REQUIRED']);
  send({ stage: 'ERROR', code,
    ...(runtimeCodes.has(error?.code) ? { runtimeCode: error.code } : {}),
    ...(['TypeError', 'SyntaxError', 'Error'].includes(error?.name) ? { type: error.name } : {}),
    ...(Number.isInteger(error?.status) && error.status >= 100 && error.status <= 599 ? { status: error.status } : {}) });
  process.exitCode = 1;
} finally { lines.close(); }
