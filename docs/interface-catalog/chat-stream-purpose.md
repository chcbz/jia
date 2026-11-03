# CHAT流式正文用途（2026-11-03）

遵循interface-standard v1.4。新增可选展示字段`streamOutcomeKind`，仅`ANSWER`/`CLARIFY`；用于已绑定的`chat.message.delta`→`agent_message_delta`。认证、资源关系、序号和事务仍走既有入口，不授予执行或完成权限。

类型来自Runtime对同一模型结构化输出顶层kind的解析，API不从正文推测。缺字段兼容旧生产者；非法字段拒绝当次delta。字段随现有事件JSON保存，无新增表。带字段delta幂等摘要绑定JSON数组`[kind,content]`，旧delta保持正文SHA算法，避免同序号修改用途被视为相同消息。最终typedOutcome校验和提交不变。

Web缺少或混合用途标识时等待final，不提前显示；具体合同见协调仓`specs/juyiting-skill-execution/native-execution-technical-design-20261027.md`“2026-11-03增补”。回归入口：ChatDeltaPurposeTest、AgentWebSocketHandlerTest；Runtime juyiting-typed-outcome-stream/juyiting-typed-runtime；Web juyiting-discussion-turns。本文记录候选合同，不代表已部署。
