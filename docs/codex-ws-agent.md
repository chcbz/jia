# codex-ws-agent 接口文档与对接方案

## 一、架构概览

```
┌─────────────────┐                    ┌─────────────────┐
│  codex-ws-agent  │  ws://host:10018  │   CYF API       │
│  (另一台服务器)  │ <──────────────> │   (port 10018)   │
│                  │  /ws/agent/channel │                 │
│  Node.js 20+     │                    │  Spring Boot    │
│  + codex CLI     │                    │  + WebSocket    │
└─────────────────┘                    └─────────────────┘
```

- 通信方式：WebSocket (JSON 文本帧)
- 认证方式：URL 参数 `api_key`
- 心跳间隔：30s，重连窗口 30 分钟（指数退避）

---

## 二、WebSocket 接口

### 2.1 连接

```
GET ws://<host>:10018/ws/agent/channel?api_key=<API_KEY>
```

成功握手后服务端下发：

```json
{"type":"connected","sessionId":"...","channel":"agent",
 "capabilities":["chat.stream","chat.stop","ping","agent.register","agent.status","agent.message","task.assign","task.report","task.event"]}
```

### 2.2 上行 (Agent → Server)

| type | 说明 | 关键字段 |
|------|------|---------|
| `agent.register` | 注册 | `agentId`,`name`,`personaName`,`abilities[]` |
| `agent.status` | 更新状态 | `agentId`,`status`(online/busy/offline/error),`currentTask?` |
| `agent.message` | 发送回复 | `agentId`,`conversationId`,`conversationType`,`content`,`senderName` |
| `agent.message.delta` | 流式增量 | `agentId`,`conversationId`,`content` |
| `task.report` | 任务回报 | `agentId`,`taskId`,`status`,`output`,`errorMessage?`,`durationMs` |
| `ping` | 心跳 | 需带 `requestId` |

### 2.3 下行 (Server → Agent)

| type | 说明 | 关键字段 |
|------|------|---------|
| `connected` | 连接成功 | `sessionId`,`capabilities[]` |
| `agent_registered` | 注册确认 | `agentId` |
| `pong` | 心跳响应 | 回显 `requestId` |
| `agent_direct_message` | 厅内传令(核心!) | `conversationId`,`conversationType`,`content`,`agentId`,`senderName`,`metadata` |
| `task_assigned` | 任务委派 | `taskId`,`title`,`status` |
| `task_event` | 任务事件 | `eventType`,`taskId`,`title`,`status` |
| `agent_status` | 广播状态 | `agentId`,`status`,`currentTask?` |

所有消息 JSON 顶层含 `type`,`requestId`,`agentId`,`senderType`,`senderName`。

### 2.4 对接流程

**注册上线：**
```
Agent ──connect──> Server
Agent <──connected── Server
Agent ──agent.register──> Server   (agentId, name, abilities)
Agent <──agent_registered── Server
Agent ──agent.status──> Server     ("online")
```

**厅内传令（聚义厅）：**
```
Server ──agent_direct_message──> Agent   {conversationId, content, agentId}
Agent ──agent.message──> Server          {content: "结果...", agentId}
Agent ──task.report──> Server            {status: "completed", output, durationMs}
```

**任务委派：**
```
Server ──task_assigned──> Agent   {taskId, title, status}
Agent 执行 codex exec ...
Agent ──task.report──> Server     {taskId, status: "completed"}
```

---

## 三、codex-ws-agent 详设

### 3.1 目录结构

```
/home/isp/apps/codex-ws-agent/
├── .env                       # 环境变量
├── .codex/                    # $CODEX_HOME（config.toml, auth.json, session_index.jsonl）
├── .codex-wuyong/             # Profile 独立 CODEX_HOME
├── .codex-linchong/
├── agent-client.mjs           # 主程序
├── codex-profiles.conf        # Profile 定义 (INI格式)
├── package.json               # {"type":"module"}
└── logs/                      # 启动+运行日志
```

### 3.2 核心机制

**多 Profile**：一个进程管理多个 Codex 实例，各自独立 WebSocket 连接。

**重连**：断开后指数退避重连 (1s→2s→4s→8s→16s→30s)，error 后 1s 内无 close 则强制重连，防止卡死。

**Codex 调用**：收到 `agent_direct_message` 或 `task_assigned` 后，通过 `spawn(codexBin, ["exec","--cd",workdir,"--skip-git-repo-check",prompt])` 执行，结果通过 WebSocket 回报。

**厅内回复**：Codex 执行完毕后自动发送 `agent.message` + `task.report`，前端聚义厅界面实时显示。

### 3.3 文件内容

#### `.env`

```bash
WS_URL=ws://<cyf-api-host>:10018/ws/agent/channel
OPENCLAW_API_KEY=cdx_xxxxxxxx
DEFAULT_CODEX_PROFILE=wuyong
CODEX_PROFILES_FILE=/home/isp/apps/codex-ws-agent/codex-profiles.conf
HEARTBEAT_MS=30000
RECONNECT_MAX_MS=1800000
```

#### `codex-profiles.conf`

```ini
[default]
codexBin=/usr/local/bin/codex
codexWorkdir=/home/isp
codexSandbox=workspace-write
codexApproval=never
codexSessionMode=resume
codexTimeoutMs=900000

[agent.wuyong]
agentId=wuyong
codexWorkdir=/home/isp/hosts/cyf/workspace/cyf
agentName=吴用
personaName=智多星
codexHome=/home/isp/apps/codex-ws-agent/.codex-wuyong
isDefault=true
```

#### `.codex/config.toml`

```toml
openai_base_url = "https://codex.chcbz.net/v1"
model = "gpt-5.4"
model_provider = "gpt"
preferred_auth_method = "apikey"

[model_providers."gpt"]
name = "GPT"
base_url = "https://codex.chcbz.net/v1"
wire_api = "responses"
requires_openai_auth = true

[projects."/home/isp/hosts/cyf/workspace/cyf"]
trust_level = "trusted"

[projects."/home/isp"]
trust_level = "trusted"
```

### 3.4 启动脚本 `codex_ws_agent_start.sh`

```bash
#!/usr/bin/env bash
# Usage: ./codex_ws_agent_start.sh {start|stop|restart|status}
# 用 tmux 或 nohup+setsid 持久化运行
```

---

## 四、新服务器部署

### 前置条件

- Node.js >= 20
- `codex` CLI 已安装并登录
- 防火墙放通到 cyf-api 10018 端口

### 步骤

```bash
# 1. 创建目录
mkdir -p /path/to/codex-ws-agent/logs
mkdir -p /path/to/codex-ws-agent/.codex-my-agent

# 2. 放入 4 个文件：agent-client.mjs, package.json, .env, codex-profiles.conf

# 3. 编辑 .env —— 修改 WS_URL 和 OPENCLAW_API_KEY

# 4. 编辑 codex-profiles.conf —— 修改 agentId, codexWorkdir, codexHome

# 5. 配置 Codex
codex login
# 或用已有 auth.json + config.toml

# 6. 启动
node agent-client.mjs
# 或用启动脚本
bash codex_ws_agent_start.sh start

# 7. 验证
tail -f logs/startlog_*.log
# 预期: websocket connected | profile=xxx | agentId=xxx
```

### 排查

| 现象 | 原因 |
|------|------|
| `non-101 status code` | API Key 无效或后端未启 |
| `reconnect window exceeded` | 30分钟未连上，检查网络/启动脚本 |
| 连接后秒断 | 后端异常（查后端 ERROR 日志） |
| 传令无回复 | 确认 `codex exec "echo test"` 可执行 |
