import { spawn } from 'node:child_process'
import { readFileSync, existsSync } from 'node:fs'
import { resolve } from 'node:path'

const envPath = resolve(process.cwd(), '.env')
if (existsSync(envPath)) {
  for (const line of readFileSync(envPath, 'utf8').split(/\r?\n/)) {
    const trimmed = line.trim()
    if (!trimmed || trimmed.startsWith('#')) continue
    const index = trimmed.indexOf('=')
    if (index < 0) continue
    const key = trimmed.slice(0, index).trim()
    const value = trimmed.slice(index + 1).trim()
    if (!process.env[key]) process.env[key] = value
  }
}

const parseNonNegativeMs = (value, fallback) => {
  const parsed = Number(value ?? fallback)
  return Number.isFinite(parsed) && parsed >= 0 ? parsed : fallback
}

const parseScalarValue = (rawValue) => {
  const value = String(rawValue || '').trim()
  if (!value) return ''
  if ((value.startsWith('"') && value.endsWith('"')) || (value.startsWith("'") && value.endsWith("'"))) {
    return value.slice(1, -1)
  }
  if (value === 'true') return true
  if (value === 'false') return false
  if (/^-?\d+(\.\d+)?$/.test(value)) return Number(value)
  return value
}

const parseSectionProfiles = (raw, fallbackProfile) => {
  const profiles = []
  let current = null
  let defaults = {}

  for (const originalLine of String(raw).split(/\r?\n/)) {
    const line = originalLine.trim()
    if (!line || line.startsWith('#')) continue

    if (/^\[(default|profile\.default|agent\.default)\]$/.test(line)) {
      if (current) profiles.push(current)
      current = { __section: 'default' }
      continue
    }

    const sectionMatch = line.match(/^\[(agent|profile)\.([^\]]+)\]$/)
    if (sectionMatch) {
      if (current) profiles.push(current)
      current = {
        profileId: sectionMatch[2]
      }
      continue
    }

    if (!current) continue

    const index = line.indexOf('=')
    if (index < 0) continue

    const key = line.slice(0, index).trim()
    const value = line.slice(index + 1).trim()
    current[key] = parseScalarValue(value)
  }

  if (current) profiles.push(current)

  const defaultIndex = profiles.findIndex(profile => profile.__section === 'default')
  if (defaultIndex >= 0) {
    defaults = { ...profiles[defaultIndex] }
    delete defaults.__section
    profiles.splice(defaultIndex, 1)
  }

  if (!profiles.length) {
    console.error('CODEX_PROFILES_FILE section format is empty or invalid')
    process.exit(1)
  }

  return profiles.map((profile, index) => normalizeProfile({ ...defaults, ...profile }, fallbackProfile, index))
}

const normalizeProfile = (profile, fallback = {}, index = 0) => {
  const agentId = profile.agentId || fallback.agentId || `local-codex-${index + 1}`
  return {
    profileId: profile.profileId || agentId,
    agentId,
    agentName: profile.agentName || fallback.agentName || `本地 Codex ${index + 1}`,
    personaName: profile.personaName || fallback.personaName || profile.agentName || fallback.agentName || `Codex ${index + 1}`,
    codexBin: profile.codexBin || fallback.codexBin || 'codex',
    codexHome: profile.codexHome || fallback.codexHome || '',
    codexWorkdir: profile.codexWorkdir || fallback.codexWorkdir || process.cwd(),
    codexSandbox: profile.codexSandbox || fallback.codexSandbox || 'workspace-write',
    codexApproval: profile.codexApproval || fallback.codexApproval || 'never',
    codexSessionMode: profile.codexSessionMode || fallback.codexSessionMode || 'new',
    codexTimeoutMs: parseNonNegativeMs(profile.codexTimeoutMs, fallback.codexTimeoutMs || 900000),
    isDefault: profile.isDefault === true
  }
}

const loadProfilesRaw = () => {
  const profilesFile = process.env.CODEX_PROFILES_FILE?.trim()
  if (profilesFile) {
    try {
      return readFileSync(resolve(profilesFile), 'utf8')
    } catch (error) {
      console.error(`failed to read CODEX_PROFILES_FILE ${profilesFile}: ${error.message}`)
      process.exit(1)
    }
  }

  return process.env.CODEX_PROFILES || ''
}

const parseProfiles = (raw, fallbackProfile) => {
  if (!raw || !String(raw).trim()) return [fallbackProfile]

  const text = String(raw).trim()
  if (/^\[(agent|profile)\./m.test(text)) {
    return parseSectionProfiles(text, fallbackProfile)
  }

  let parsed
  try {
    parsed = JSON.parse(text)
  } catch (error) {
    console.error(`CODEX_PROFILES must be valid JSON: ${error.message}`)
    process.exit(1)
  }

  if (!Array.isArray(parsed) || parsed.length === 0) {
    console.error('CODEX_PROFILES must be a non-empty JSON array')
    process.exit(1)
  }

  return parsed.map((profile, index) => normalizeProfile(profile || {}, fallbackProfile, index))
}

const legacyProfile = normalizeProfile({
  profileId: process.env.CODEX_PROFILE_ID || process.env.AGENT_ID || 'default',
  agentId: process.env.AGENT_ID || 'local-codex',
  agentName: process.env.AGENT_NAME || '本地 Codex',
  personaName: process.env.AGENT_PERSONA || '吴用',
  codexBin: process.env.CODEX_BIN || 'codex',
  codexHome: process.env.CODEX_HOME || '',
  codexWorkdir: process.env.CODEX_WORKDIR || process.cwd(),
  codexSandbox: process.env.CODEX_SANDBOX || 'workspace-write',
  codexApproval: process.env.CODEX_APPROVAL || 'never',
  codexSessionMode: process.env.CODEX_SESSION_MODE || 'new',
  codexTimeoutMs: parseNonNegativeMs(process.env.CODEX_TIMEOUT_MS, 900000),
  isDefault: true
})

const profiles = parseProfiles(loadProfilesRaw(), legacyProfile)
const defaultProfileId = process.env.DEFAULT_CODEX_PROFILE
  || profiles.find(profile => profile.isDefault)?.profileId
  || profiles[0].profileId

const config = {
  wsUrl: process.env.WS_URL || 'ws://127.0.0.1:10018/ws/agent/channel',
  apiKey: process.env.OPENCLAW_API_KEY || '',
  profiles,
  defaultProfileId,
  heartbeatMs: Number(process.env.HEARTBEAT_MS || 30000),
  reconnectMaxMs: parseNonNegativeMs(process.env.RECONNECT_MAX_MS, 30 * 60 * 1000)
}

if (!config.apiKey) {
  console.error('OPENCLAW_API_KEY is required')
  process.exit(1)
}

const currentRuns = new Map()
let shuttingDown = false

const withApiKey = (url) => {
  const parsed = new URL(url)
  parsed.searchParams.set('api_key', config.apiKey)
  return parsed.toString()
}

const getProfileById = (profileId) => config.profiles.find(profile => profile.profileId === profileId || profile.agentId === profileId)

const defaultProfile = getProfileById(config.defaultProfileId) || config.profiles[0]

const ensureProfiles = () => {
  const seenProfileIds = new Set()
  const seenAgentIds = new Set()
  for (const profile of config.profiles) {
    if (seenProfileIds.has(profile.profileId)) {
      console.error(`duplicate CODEX_PROFILES profileId: ${profile.profileId}`)
      process.exit(1)
    }
    if (seenAgentIds.has(profile.agentId)) {
      console.error(`duplicate CODEX_PROFILES agentId: ${profile.agentId}`)
      process.exit(1)
    }
    seenProfileIds.add(profile.profileId)
    seenAgentIds.add(profile.agentId)
  }
  if (!defaultProfile) {
    console.error(`DEFAULT_CODEX_PROFILE not found: ${config.defaultProfileId}`)
    process.exit(1)
  }
}

ensureProfiles()

const createProfileState = (profile) => ({
  profile,
  ws: null,
  heartbeatTimer: null,
  reconnectTimer: null,
  reconnectAttempt: 0,
  reconnectStartedAt: 0
})

const profileStates = new Map(config.profiles.map(profile => [profile.agentId, createProfileState(profile)]))

const getProfileState = (profile) => profileStates.get(profile.agentId)

const send = (type, payload = {}, profile = defaultProfile) => {
  const state = getProfileState(profile)
  if (!state?.ws || state.ws.readyState !== WebSocket.OPEN) return
  state.ws.send(JSON.stringify({
    type,
    requestId: `${type}-${Date.now()}`,
    agentId: profile?.agentId || defaultProfile.agentId,
    senderType: 'agent',
    senderName: profile?.agentName || defaultProfile.agentName,
    ...payload
  }))
}

const sendStatus = (profile, status, extra = {}) => {
  send('agent.status', {
    status,
    currentTaskId: extra.taskId || '',
    currentTaskTitle: extra.title || '',
    errorMessage: extra.errorMessage || ''
  }, profile)
}

const registerAgent = (profile) => {
  send('agent.register', {
    agentId: profile.agentId,
    name: profile.agentName,
    personaName: profile.personaName,
    endpoint: config.wsUrl,
    abilities: ['codex', 'shell', 'code-edit', 'debug', 'deploy-assist']
  }, profile)
}

const resolvePrompt = (message) => {
  if (message.prompt) return String(message.prompt)
  if (message.content) return String(message.content)
  if (message.description) return String(message.description)
  if (message.currentTaskTitle) return String(message.currentTaskTitle)
  if (message.title) return `处理任务：${message.title}`
  return ''
}

const trimReply = (value, limit = 12000) => {
  const text = String(value || '').trim()
  if (!text) return ''
  return text.length > limit ? `${text.slice(0, limit)}\n\n[输出已截断]` : text
}

const sleep = (ms) => new Promise(resolve => setTimeout(resolve, ms))

const buildReplyChunks = (content, chunkSize = 72) => {
  const text = String(content || '')
  if (!text) return []
  const chunks = []
  for (let index = 0; index < text.length; index += chunkSize) {
    chunks.push(text.slice(index, index + chunkSize))
  }
  return chunks
}

const sendAgentReplyDelta = (profile, message, content, extra = {}) => {
  if (!content) return
  send('agent.message.delta', {
    conversationId: message.conversationId,
    conversationType: message.conversationType || 'juyiting',
    content,
    agentId: profile.agentId,
    senderName: profile.personaName || profile.agentName,
    ...extra
  }, profile)
}

const resolveProfileFromMessage = (message) => {
  const candidates = [
    message.cliProfile,
    message.codexProfile,
    message.profileId,
    message.profile,
    message.assignedAgentId,
    message.targetAgentId,
    message.receiverAgentId,
    message.agentId
  ].filter(Boolean)

  for (const candidate of candidates) {
    const profile = getProfileById(String(candidate))
    if (profile) return profile
  }

  return defaultProfile
}

const shouldRun = (message, profile) => {
  if (!profile) return false
  if (currentRuns.has(profile.agentId)) return false
  if (message.type === 'agent_direct_message') return true
  if (['codex.exec', 'task.assign', 'task_assigned'].includes(message.type)) return true
  if (message.type === 'task_event') {
    const assignedToMe = !message.assignedAgentId || message.assignedAgentId === profile.agentId
    return assignedToMe && ['running', 'pending', 'assigned'].includes(String(message.status || '').toLowerCase())
  }
  return false
}

const hasCodexSession = (profile) => {
  if (!profile.codexHome) return false
  const indexPath = resolve(profile.codexHome, 'session_index.jsonl')
  try {
    return existsSync(indexPath) && readFileSync(indexPath, 'utf8').trim().length > 0
  } catch {
    return false
  }
}

const buildCodexArgs = (profile, prompt) => {
  const resume = profile.codexSessionMode === 'resume' && hasCodexSession(profile)
  if (resume) {
    return [
      '--ask-for-approval', profile.codexApproval,
      'exec',
      'resume',
      '--last',
      '--all',
      '--skip-git-repo-check',
      prompt
    ]
  }

  return [
    '--ask-for-approval', profile.codexApproval,
    'exec',
    '--cd', profile.codexWorkdir,
    '--sandbox', profile.codexSandbox,
    '--skip-git-repo-check',
    prompt
  ]
}

const runCodex = (profile, message) => {
  const prompt = resolvePrompt(message)
  if (!prompt) {
    send('codex.result', {
      status: 'failed',
      message: 'No prompt/content/title found in inbound event'
    }, profile)
    return
  }

  const taskId = message.taskId || message.id || message.requestId || `codex-${Date.now()}`
  const title = message.title || message.currentTaskTitle || 'Codex 执行任务'
  sendStatus(profile, 'busy', { taskId, title })
  if (message.type === 'agent_direct_message') {
    sendAgentReplyDelta(profile, message, '收到，正在整理回报。\n\n', { phase: 'intro' })
  }

  const args = buildCodexArgs(profile, prompt)
  console.log(`starting codex run | profile=${profile.profileId} | agentId=${profile.agentId} | type=${message.type} | taskId=${taskId} | conversationId=${message.conversationId || ''}`)

  const startedAt = Date.now()
  const child = spawn(profile.codexBin, args, {
    cwd: profile.codexWorkdir,
    env: {
      ...process.env,
      ...(profile.codexHome ? { CODEX_HOME: profile.codexHome } : {})
    },
    stdio: ['ignore', 'pipe', 'pipe']
  })

  currentRuns.set(profile.agentId, child)
  let stdout = ''
  let stderr = ''
  const timeout = setTimeout(() => {
    child.kill('SIGTERM')
  }, profile.codexTimeoutMs)

  child.stdout.on('data', chunk => {
    stdout += chunk.toString()
    if (stdout.length > 120000) stdout = stdout.slice(-120000)
  })

  child.stderr.on('data', chunk => {
    stderr += chunk.toString()
    if (stderr.length > 60000) stderr = stderr.slice(-60000)
  })

  child.on('close', async code => {
    clearTimeout(timeout)
    currentRuns.delete(profile.agentId)
    const status = code === 0 ? 'completed' : 'failed'
    const replyContent = trimReply(stdout) || trimReply(stderr) || (code === 0 ? '已处理，但无可返回内容。' : '执行失败，暂无详细输出。')
    console.log(`codex run finished | profile=${profile.profileId} | status=${status} | code=${code} | conversationId=${message.conversationId || ''}`)
    const payload = {
      taskId,
      agentId: profile.agentId,
      status,
      currentTaskTitle: title,
      durationMs: Date.now() - startedAt,
      output: stdout.trim(),
      errorMessage: stderr.trim()
    }
    if (message.type === 'agent_direct_message') {
      const chunks = buildReplyChunks(replyContent)
      for (const [index, chunk] of chunks.entries()) {
        sendAgentReplyDelta(profile, message, chunk, {
          phase: 'reply',
          chunkIndex: index,
          chunkCount: chunks.length
        })
        await sleep(index === 0 ? 40 : 65)
      }
      console.log(`sending agent.message | conversationId=${message.conversationId || ''}`)
      send('agent.message', {
        conversationId: message.conversationId,
        conversationType: message.conversationType || 'juyiting',
        content: replyContent,
        agentId: profile.agentId,
        senderName: profile.personaName || profile.agentName
      }, profile)
    }
    send('task.report', payload, profile)
    send('codex.result', payload, profile)
    sendStatus(profile, 'online')
  })

  child.on('error', error => {
    clearTimeout(timeout)
    currentRuns.delete(profile.agentId)
    console.error(`codex run error | profile=${profile.profileId} | conversationId=${message.conversationId || ''} | ${error.message}`)
    const payload = {
      taskId,
      agentId: profile.agentId,
      status: 'failed',
      currentTaskTitle: title,
      errorMessage: error.message
    }
    if (message.type === 'agent_direct_message') {
      send('agent.message', {
        conversationId: message.conversationId,
        conversationType: message.conversationType || 'juyiting',
        content: `执行失败：${error.message}`,
        agentId: profile.agentId,
        senderName: profile.personaName || profile.agentName
      }, profile)
    }
    send('task.report', payload, profile)
    send('codex.result', payload, profile)
    sendStatus(profile, 'online')
  })
}

const handleMessage = (profile, raw) => {
  let message
  try {
    message = JSON.parse(raw.toString())
  } catch {
    message = { type: 'codex.exec', content: raw.toString() }
  }

  if (message.type === 'connected') {
    console.log(`websocket connected | profile=${profile.profileId} | agentId=${profile.agentId}`)
    registerAgent(profile)
    sendStatus(profile, currentRuns.has(profile.agentId) ? 'busy' : 'online')
    return
  }
  if (message.type === 'ping') {
    send('pong', {}, profile)
    return
  }
  if (message.type === 'agent_direct_message') {
    console.log(`received agent_direct_message | profile=${profile.profileId} | conversationId=${message.conversationId || ''}`)
  }
  const targetProfile = resolveProfileFromMessage(message)
  if (targetProfile.agentId !== profile.agentId) {
    return
  }
  if (shouldRun(message, targetProfile)) {
    runCodex(targetProfile, message)
  }
}

const doReconnect = (profile) => {
  const state = getProfileState(profile)
  if (!state) return
  const now = Date.now()
  if (!state.reconnectStartedAt) state.reconnectStartedAt = now
  const elapsed = now - state.reconnectStartedAt
  if (elapsed >= config.reconnectMaxMs) {
    console.error(`reconnect window exceeded ${config.reconnectMaxMs}ms, stop retrying | profile=${profile.profileId}`)
    return
  }

  const delay = Math.min(30000, 1000 * 2 ** state.reconnectAttempt, config.reconnectMaxMs - elapsed)
  state.reconnectAttempt += 1
  console.warn(`reconnecting in ${delay}ms | profile=${profile.profileId} | attempt ${state.reconnectAttempt} | elapsed ${(elapsed / 1000).toFixed(0)}s`)
  state.reconnectTimer = setTimeout(() => connectProfile(profile), delay)
}

const connectProfile = (profile) => {
  const state = getProfileState(profile)
  if (!state) return

  clearTimeout(state.reconnectTimer)

  if (state.ws && state.ws.readyState !== WebSocket.CLOSED) {
    try { state.ws.close() } catch {}
  }

  let closeFired = false

  state.ws = new WebSocket(withApiKey(config.wsUrl))

  state.ws.addEventListener('open', () => {
    closeFired = true
    state.reconnectAttempt = 0
    state.reconnectStartedAt = 0
    console.log(`websocket open | profile=${profile.profileId} | agentId=${profile.agentId}`)
    registerAgent(profile)
    sendStatus(profile, currentRuns.has(profile.agentId) ? 'busy' : 'online')
    state.heartbeatTimer = setInterval(() => {
      sendStatus(profile, currentRuns.has(profile.agentId) ? 'busy' : 'online')
    }, config.heartbeatMs)
  })

  state.ws.addEventListener('message', event => handleMessage(profile, event.data))

  state.ws.addEventListener('close', () => {
    closeFired = true
    clearInterval(state.heartbeatTimer)
    state.heartbeatTimer = null
    if (shuttingDown) return
    doReconnect(profile)
  })

  state.ws.addEventListener('error', error => {
    console.error(`websocket error | profile=${profile.profileId}:`, error.message || error)
    setTimeout(() => {
      if (!closeFired && !shuttingDown) {
        console.warn(`websocket error without close, forcing reconnect | profile=${profile.profileId}`)
        if (state.ws && state.ws.readyState !== WebSocket.CLOSED) {
          try { state.ws.close() } catch {}
        }
        doReconnect(profile)
      }
    }, 1000)
  })
}

const shutdown = () => {
  shuttingDown = true
  for (const profile of config.profiles) {
    const state = getProfileState(profile)
    clearTimeout(state?.reconnectTimer)
    clearInterval(state?.heartbeatTimer)
    sendStatus(profile, 'offline')
    state?.ws?.close()
  }
  process.exit(0)
}

process.on('SIGINT', () => {
  shutdown()
})

process.on('SIGTERM', () => {
  shutdown()
})

for (const profile of config.profiles) {
  connectProfile(profile)
}
