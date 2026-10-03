#!/bin/sh
set -eu
: "${CYF_CLIENT_ROOT:?}" "${CYF_FIXTURE_RUN_ROOT:?}" "${CYF_CLIENT_WS_URL:?}" "${CYF_FIXTURE_API_KEY:?}" "${CYF_FIXTURE_AGENT_ID:?}"
case "$CYF_CLIENT_WS_URL" in
  ws://127.0.0.1:*|ws://localhost:*) ;;
  wss://*) ;;
  *) echo 'plaintext Client websocket is allowed only on loopback; private/non-loopback requires wss' >&2; exit 2 ;;
esac
case "$CYF_FIXTURE_AGENT_ID" in agt_[0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f]) ;; *) echo 'canonical fixture agent id is required' >&2; exit 2;; esac
root=$(realpath "$CYF_FIXTURE_RUN_ROOT")
client_root=$(realpath "$CYF_CLIENT_ROOT")
mkdir -p "$root/client/inbox" "$root/client/codex-home" "$root/client/workdir"
chmod 700 "$root" "$root/client" "$root/client/inbox" "$root/client/codex-home" "$root/client/workdir"
unset CODEX_PROFILES CODEX_PROFILES_FILE DEFAULT_CODEX_PROFILE
export WS_URL="$CYF_CLIENT_WS_URL"
export OPENCLAW_API_KEY="$CYF_FIXTURE_API_KEY"
export CODEX_PROFILE_ID='archive-real-runtime'
export AGENT_ID="$CYF_FIXTURE_AGENT_ID"
export AGENT_NAME='Fixture Editor'
export AGENT_PERSONA='Fixture Editor'
export CODEX_BIN='/bin/false'
export CODEX_HOME="$root/client/codex-home"
export CODEX_WORKDIR="$root/client/workdir"
export CODEX_PROFILE_RELOAD_MS=0
export COMMAND_INBOX_DIR="$root/client/inbox"
export COMMAND_INBOX_SUCCESS_POLICY='archive'
export HEARTBEAT_MS=5000
export REGISTRATION_ACK_TIMEOUT_MS=15000
export AGENT_PLATFORM_SKILL_INSTALL_ENABLED=true
export AGENT_ARCHIVE_MAINTENANCE_ENABLED=true
export AGENT_SKILL_INSTALL_ENABLED=false
exec node "$client_root/conf/codex-ws-agent/agent-client.mjs"
