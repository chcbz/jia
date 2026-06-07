# CYF 项目部署说明

## 一、项目拓扑

```
                     ┌────────────────────┐
   用户浏览器 ────> │ kit.chaoyoufan.cn   │  (443)  nginx
                     │ root: /cyf/web/kit  │
                     └────────┬───────────┘
                              │ /api 代理
                              ▼
                     ┌────────────────────┐
                     │ 127.0.0.1:10018    │  Java Spring Boot
                     │ (cyf-api-kit.jar)  │
                     │ profiles: prod     │
                     └────────┬───────────┘
                              │ ws://10018/ws/agent/channel
                              ▼
                     ┌────────────────────┐
                     │ codex-ws-agent     │  Node.js
                     │ agent-client.mjs   │  → codex CLI
                     └────────────────────┘
```

**项目仓库：**

| 项目 | Git 仓库 | 本地路径 |
|------|---------|---------|
| 后端 API | `https://gitee.com/chcbz/jia.git` (develop) | `/home/isp/hosts/cyf/workspace/cyf/api` |
| 前端 Web | `https://gitee.com/chcbz/cyf-web-kit.git` (develop) | `/home/isp/hosts/cyf/workspace/cyf/web` |

---

## 二、服务器环境

| 组件 | 路径/配置 |
|------|----------|
| JDK | `/home/isp/apps/jdk21` (Java 21) |
| Node.js | `/home/isp/apps/node/bin/node` (v20) |
| Gradle | `/root/.codex/memories/gradle-9.3.1/bin/gradle` |
| MySQL | socket: `/home/isp/apps/mysql/mysql.sock`, port 3306 |
| Redis | 127.0.0.1:6379 |
| Nginx | `/home/isp/apps/nginx` |
| 部署目录 | `/home/isp/hosts/cyf/api` (后端), `/home/isp/hosts/cyf/web/kit` (前端) |

---

## 三、快速发布流程

### 后端

```bash
# 一条命令：拉代码 → 构建 → 部署 → 重启
bash /home/isp/bin/cyf_api_kit_start.sh
```

**脚本内部做了什么：**
1. `git pull` 拉取 develop 分支最新代码
2. `gradle :starter:bootJar -x test --no-daemon` 构建 JAR
3. `tar zcf package.tgz` 打包
4. 解压 → 替换 `cyf-api-kit.jar`
5. 停止旧进程 → 启动新进程（nohup+setsid）

**JVM 参数：**
```
-Xms128m -Xmx512m -Xss256k -XX:MaxMetaspaceSize=192m -XX:+UseG1GC
```

**启动参数：**
```
--server.port=10018 --spring.profiles.active=prod
```

### 前端

```bash
# 一条命令：拉代码 → npm install → build → 部署
bash /home/isp/bin/cyf_web_kit_start.sh
```

**脚本内部做了什么：**
1. `git pull` 拉取 develop 分支
2. `npm install` 安装依赖（如有变化）
3. `npm run build` (= `vite build`)
4. 旧版本备份到 `/home/isp/hosts/cyf/web/bak/`
5. `dist/` 复制到 `/home/isp/hosts/cyf/web/kit/`（nginx 根目录）

### codex-ws-agent

```bash
# 重启 agent（通常随 API 重启后自动重连，手动操作少）
bash /home/isp/bin/codex_ws_agent_start.sh restart

# 查看状态
bash /home/isp/bin/codex_ws_agent_start.sh status
```

---

## 四、常用运维操作

### 查看进程

```bash
# 后端
ps -ef | grep cyf-api-kit | grep -v grep

# 前端（纯静态，无进程）

# agent
ps -ef | grep agent-client | grep -v grep
```

### 查看日志

```bash
# 后端启动日志
ls -t /home/isp/hosts/cyf/api/logs/startlog_*.log | head -1 | xargs tail -50

# agent 日志
tail -f /home/isp/apps/codex-ws-agent/logs/startlog_*.log

# nginx 访问日志
tail -f /home/isp/logs/access/kit.chaoyoufan.cn.log
```

### 数据库维护

```bash
# 连接 MySQL
/home/isp/apps/mysql/bin/mysql -S /home/isp/apps/mysql/mysql.sock jia

# 查看表结构
SHOW CREATE TABLE chat_conversation;
SHOW CREATE TABLE chat_message;

# 执行迁移 SQL
source /home/isp/hosts/cyf/workspace/cyf/api/chat/jia-chat-mapper/src/main/resources/db/openclaw-channel-migration.sql
```

### 重启 nginx

```bash
/home/isp/apps/nginx/sbin/nginx -s reload
```

---

## 五、数据库变更注意事项

代码中如有新增字段的 Entity，需要同步执行 `ALTER TABLE`。迁移 SQL 文件在：
- `api/chat/jia-chat-mapper/src/main/resources/db/openclaw-channel-migration.sql`
- `api/agent/jia-agent-mapper/src/main/resources/db/ability-evaluation-schema.sql`

**历史教训：** `chat_conversation` 和 `chat_message` 表加了 `conversation_type` 等字段但 SQL 未执行，导致 `AgentStatusMonitor` 定时任务抛异常，agent 连接状态无法维护。

---

## 六、部署清单

| 检查项 | 命令/位置 |
|--------|----------|
| 后端进程 | `ps -ef \| grep cyf-api-kit` |
| 后端端口 | `curl -s -o /dev/null -w "%{http_code}" http://127.0.0.1:10018/actuator` |
| agent 连接 | `tail -2 /home/isp/apps/codex-ws-agent/logs/startlog_*.log` 含 `connected` |
| 前端页面 | `curl -k -s -o /dev/null -w "%{http_code}" https://kit.chaoyoufan.cn/` |
| nginx | `/home/isp/apps/nginx/sbin/nginx -t` |
| 数据库 | `/home/isp/apps/mysql/bin/mysql -S /home/isp/apps/mysql/mysql.sock jia -e "SELECT 1"` |
| Redis | `redis-cli -s /home/isp/apps/redis/redis.sock ping` |

## 七、常见问题速查

| 现象 | 排查 |
|------|------|
| 前端 500 | nginx 日志 `access.log`；文件权限 `chmod -R a+rX /home/isp/hosts/cyf/web/kit/` |
| agent 连不上 | 检查 `tail -20 /home/isp/apps/codex-ws-agent/logs/*.log`；确认 `curl -I http://127.0.0.1:10018/ws/agent/channel?api_key=...` |
| API 500 | `tail -100 /home/isp/hosts/cyf/api/logs/startlog_*.log \| grep ERROR` |
| 数据库报错 | 检查断新字段是否已执行迁移 SQL |
| JVM OOM | `dmesg -T \| grep -i oom`；调整 `-Xmx512m` |
