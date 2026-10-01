import assert from 'node:assert/strict'
import { createHash } from 'node:crypto'
import { chmod, mkdir, mkdtemp, readFile, rename, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { dirname, join, resolve } from 'node:path'
import { pathToFileURL } from 'node:url'
import test from 'node:test'

const required = name => {
  const value = process.env[name]
  if (typeof value !== 'string' || value.length === 0 || value !== value.trim()) throw new Error(`${name} is required`)
  return value
}
const sha256 = bytes => createHash('sha256').update(bytes).digest('hex')
const atomicJson = async (path, value) => {
  await mkdir(dirname(path), { recursive: true })
  const temporary = `${path}.tmp-${process.pid}`
  await writeFile(temporary, `${JSON.stringify(value)}\n`, { mode: 0o600, flag: 'wx' })
  await chmod(temporary, 0o600)
  await rename(temporary, path)
}

const clientRoot = resolve(required('CYF_CLIENT_ROOT'))
const commandFile = resolve(required('CYF_FIXTURE_COMMAND_FILE'))
const resultFile = resolve(required('CYF_FIXTURE_CLIENT_RESULT_FILE'))
const wsUrl = required('CYF_FIXTURE_WS_URL')
const runtimeToken = required('CYF_FIXTURE_RUNTIME_TOKEN')
assert.match(runtimeToken, /^[0-9a-f]{32}$/u)
assert.match(wsUrl, /^ws:\/\/(?:127(?:\.\d{1,3}){3}|localhost|\[::1\]):[1-9][0-9]*\/ws$/u)

const importClient = relative => import(pathToFileURL(join(clientRoot, 'conf', 'codex-ws-agent', relative)).href)
const approvedPath = join(clientRoot, 'conf', 'codex-ws-agent', 'test', 'fixtures',
  'archive-maintainer-1.0.0-approved.zip')

const runtimeScope = Object.freeze({ scheme: 'native-runtime-v1', tenantId: '0', clientId: 'client-a',
  ownerJiacn: 'owner-a', agentId: 'agent-a', runtimeInstanceId: 'runtime-a' })
const profile = Object.freeze({ profileId: 'fixture-profile', agentId: 'agent-a', agentName: 'Fixture Agent', personaName: 'Fixture Agent' })

const platformCommandId = installationId => `cmd_controlled_${sha256(Buffer.from(
  ['0', 'client-a', 'owner-a', installationId, 'agent-a', 'PLATFORM_SKILL_INSTALL'].join('\0')))}`

const installApprovedPackage = async ({ PlatformSkillManager, root, packageBytes, contract }) => {
  const codexHome = join(root, 'codex-home')
  await mkdir(codexHome, { recursive: true, mode: 0o700 })
  const manager = new PlatformSkillManager({
    profile: { ...profile, codexHome }, runtimeScope, stateRoot: join(root, 'platform-state'),
    wsUrl, enabled: true, authorizationProvider: () => `AgentRuntime ${runtimeToken}`,
    downloadFn: async ({ command }) => {
      assert.equal(command.installationId, contract.installationId)
      assert.equal(command.packageSha256, contract.packageSha256)
      return packageBytes
    },
    sendResultFn: async ({ command, outcome, errorCode }) => ({
      installationId: command.installationId, agentId: command.targetAgentId,
      bindingVersion: command.bindingVersion, skillKey: command.skillKey,
      skillVersion: command.skillVersion, packageSha256: command.packageSha256,
      origin: 'PLATFORM_PROVISIONED', state: outcome, errorCode, revision: '1'
    })
  })
  manager.initialize()
  const now = Date.now()
  const command = {
    schemaVersion: 1, messageType: 'command.dispatch', messageId: 'fixture-install-message',
    commandId: platformCommandId(contract.installationId), correlationId: contract.installationId,
    causationId: 'fixture-install-challenge', tenantId: '0', clientId: 'client-a', ownerJiacn: 'owner-a',
    taskId: contract.installationId, workItemId: null, targetAgentId: 'agent-a',
    commandType: 'PLATFORM_SKILL_INSTALL', issuedAt: now - 1000, expiresAt: now + 3_599_000,
    attempt: 1, fencingToken: '1', deliveryEpoch: '1', executionEpoch: '1',
    payload: { schemaVersion: 1, installationId: contract.installationId, bindingVersion: '7',
      skillKey: 'archive-maintainer', skillVersion: '1.0.0', packageSha256: contract.packageSha256,
      challengeId: 'fixture-install-challenge',
      packageRef: `/internal/agent/platform-skills/installations/${contract.installationId}/package` }
  }
  const installed = await manager.execute(command)
  assert.equal(installed.status, 'completed', installed.errorMessage)
  const proof = manager.resolveApprovedArchiveInstallation(contract.installationId)
  assert.equal(proof.packageSha256, contract.packageSha256)
  return manager
}

test('bounded real HTTP/JDBC fixture uses approved atomic install and deterministic runner', async () => {
  const root = await mkdtemp(join(tmpdir(), 'cyf-archive-cross-component-'))
  try {
    const [{ PlatformSkillManager }, { ArchiveMaintenanceRunner }] = await Promise.all([
      importClient('platform-skill-manager.mjs'), importClient('archive-maintenance-runner.mjs')
    ])
    const contract = JSON.parse(await readFile(commandFile, 'utf8'))
    assert.equal(contract.schemaVersion, 1)
    const packageBytes = await readFile(approvedPath)
    assert.equal(packageBytes.length, 40563)
    assert.equal(sha256(packageBytes), '8894d96341067dd7f9e2f45696eef44057dc61346255a0323b2d713a3c7ea081')
    assert.equal(contract.packageSha256, sha256(packageBytes))

    const manager = await installApprovedPackage({ PlatformSkillManager, root, packageBytes, contract })
    const runnerOptions = { runtimeScope, wsUrl, authorizationProvider: () => `AgentRuntime ${runtimeToken}`,
      platformSkillManager: manager }
    const manualRunner = new ArchiveMaintenanceRunner(runnerOptions)
    const manual = await manualRunner.execute(contract.manual)
    assert.equal(manual.status, 'completed', manual.errorMessage)

    const autoRunner = new ArchiveMaintenanceRunner(runnerOptions)
    const auto = await autoRunner.execute(contract.auto)
    assert.equal(auto.status, 'completed', auto.errorMessage)

    const replayRunner = new ArchiveMaintenanceRunner(runnerOptions)
    const autoReplay = await replayRunner.execute(contract.auto)
    assert.equal(autoReplay.status, 'completed', autoReplay.errorMessage)
    assert.equal(replayRunner.reconcileCommandOutcome(contract.auto)?.authoritative, true)

    await atomicJson(resultFile, { schemaVersion: 1, manualStatus: manual.status,
      autoStatus: auto.status, autoReplayStatus: autoReplay.status,
      packageSha256: sha256(packageBytes), installationId: contract.installationId })
  } finally {
    await rm(root, { recursive: true, force: true })
  }
})
