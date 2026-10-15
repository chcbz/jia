# 接口库存逐项清单（2026-10-15；非剩余任务表）

此清单直接展开当前审计中尚未登记关闭的596条operation，按模块/Controller逐条列出，不再用“两类”代替接口明细。**这是待收口审计清单，不是596条都要改代码，更不是596条都未看过。**

## 统计与口径

- 596条直接声明接口，99个Controller；221条已有人工调用关系、375条尚未建立。
- 10条第三方回调保留提供方wire，5条已退役410保持拒绝；两者均不应套普通接口重写。
- 69条现有目标method/path变更；其余路径不变不代表字段无需对齐。
- 16条挂接结构schema，结构检查不替代授权/事务设计，也不需要等开发后测试才能形成开发前结论。
- 旧审计将全部条目标为NOT_FIELD_CLOSED，未记录更细的逐项关闭结论；因此不能再声称只剩少数收尾接口，也不能将每条的字段/权限/错误/消费者都说成完全未知。已有详设/消费绑定在下表保留。
- 不含框架自动注册接口：已发现的OAuth候选`/login/oauth2/code/jia-client-oidc`单列末尾，不能混算进596。

## 模块汇总

| 模块 | 条数 | 有调用关系 | 未建立调用关系 |
| --- | ---: | ---: | ---: |
| agent | 179 | 122 | 57 |
| base | 7 | 0 | 7 |
| chat | 70 | 56 | 14 |
| dwz | 7 | 2 | 5 |
| economy | 4 | 2 | 2 |
| isp | 61 | 0 | 61 |
| kefu | 11 | 2 | 9 |
| material | 39 | 7 | 32 |
| oauth | 13 | 1 | 12 |
| point | 14 | 6 | 8 |
| sms | 17 | 0 | 17 |
| task | 12 | 6 | 6 |
| user | 80 | 6 | 74 |
| workflow | 25 | 0 | 25 |
| wx | 57 | 11 | 46 |

## agent

### AgentBountySettlementController（2条）

| 序号 | 当前method/path | 目标method/path | 响应/边界 | 源码消费关系 | 已有详设/结构证据 |
| ---: | --- | --- | --- | --- | --- |
| 1 | `POST /agent/tasks/{taskId}/funding/complete` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 2 | `GET /agent/tasks/{taskId}/settlement` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |

### AgentCollaborationRequestController（3条）

| 序号 | 当前method/path | 目标method/path | 响应/边界 | 源码消费关系 | 已有详设/结构证据 |
| ---: | --- | --- | --- | --- | --- |
| 3 | `POST /agent/hall/collaboration/requests` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 4 | `GET /agent/hall/collaboration/requests/request` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 5 | `GET /agent/hall/collaboration/requests/{requestId}` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |

### AgentCommandOperationsCapabilitiesController（1条）

| 序号 | 当前method/path | 目标method/path | 响应/边界 | 源码消费关系 | 已有详设/结构证据 |
| ---: | --- | --- | --- | --- | --- |
| 6 | `GET /agent/internal/command-operations/capabilities` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |

### AgentCommandOperationsController（7条）

| 序号 | 当前method/path | 目标method/path | 响应/边界 | 源码消费关系 | 已有详设/结构证据 |
| ---: | --- | --- | --- | --- | --- |
| 7 | `GET /agent/internal/command-operations/audit` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 8 | `POST /agent/internal/command-operations/deliveries/{deliveryId}/reissue` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 9 | `GET /agent/internal/command-operations/dlq` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 10 | `POST /agent/internal/command-operations/dlq/{deliveryId}/redrive` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 11 | `GET /agent/internal/command-operations/metrics` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 12 | `POST /agent/internal/command-operations/v1/dlq/{deliveryId}/redrive` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 13 | `GET /agent/internal/command-operations/v1/operations/{operationId}` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |

### AgentController（38条）

| 序号 | 当前method/path | 目标method/path | 响应/边界 | 源码消费关系 | 已有详设/结构证据 |
| ---: | --- | --- | --- | --- | --- |
| 14 | `GET /agent/capabilities` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 15 | `POST /agent/compare` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 16 | `POST /agent/dialogue` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 17 | `POST /agent/evaluate` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 18 | `GET /agent/evaluation/latest/{agentName}` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 19 | `GET /agent/evaluation/stats` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 20 | `GET /agent/evaluation/{agentName}` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 21 | `DELETE /agent/evaluation/{id}` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 22 | `GET /agent/list` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 23 | `GET /agent/map` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有2条源码关系；未证明全部调用覆盖 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 24 | `GET /agent/persona/{name}` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 25 | `GET /agent/personas` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 26 | `POST /agent/personas/bindings/{bindingId}/repair` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 27 | `GET /agent/personas/catalog` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 28 | `DELETE /agent/personas/{personaCode}/bind` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 29 | `POST /agent/personas/{personaCode}/bind` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有2条源码关系；未证明全部调用覆盖 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 30 | `POST /agent/register` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 31 | `POST /agent/roster` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有4条源码关系；未证明全部调用覆盖 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 32 | `GET /agent/stats` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 33 | `POST /agent/tasks` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 34 | `POST /agent/tasks/search` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有2条源码关系；未证明全部调用覆盖 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 35 | `POST /agent/tasks/status-counts` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 36 | `GET /agent/tasks/{taskId}` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有4条源码关系；未证明全部调用覆盖 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 37 | `POST /agent/tasks/{taskId}/archive` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 38 | `POST /agent/tasks/{taskId}/assign` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有2条源码关系；未证明全部调用覆盖 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 39 | `POST /agent/tasks/{taskId}/auto-assign` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 40 | `POST /agent/tasks/{taskId}/claim` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 41 | `POST /agent/tasks/{taskId}/execution-authorizations/{grantId}/revoke` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 42 | `POST /agent/tasks/{taskId}/funding/cancel` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 43 | `GET /agent/tasks/{taskId}/notes` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 44 | `POST /agent/tasks/{taskId}/notes` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 45 | `POST /agent/tasks/{taskId}/quotes` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 46 | `POST /agent/tasks/{taskId}/recommend` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 47 | `POST /agent/tasks/{taskId}/report` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 48 | `POST /agent/tasks/{taskId}/team-recommendation` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 49 | `GET /agent/{agentId}` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 50 | `PUT /agent/{agentId}/status` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 51 | `GET /agent/{agentId}/tasks` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |

### AgentHostingRentController（4条）

| 序号 | 当前method/path | 目标method/path | 响应/边界 | 源码消费关系 | 已有详设/结构证据 |
| ---: | --- | --- | --- | --- | --- |
| 52 | `POST /agent/hosting-leases/{leaseId}/renewal-quotes` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 53 | `POST /agent/hosting-leases/{leaseId}/renewals` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 54 | `POST /agent/personas/{personaCode}/hosting-rent/quotes` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 55 | `GET /agent/{agentId}/hosting-lease` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |

### AgentRuntimeV1Controller（7条）

| 序号 | 当前method/path | 目标method/path | 响应/边界 | 源码消费关系 | 已有详设/结构证据 |
| ---: | --- | --- | --- | --- | --- |
| 56 | `POST /agent/runtime/v1/commands/{messageId}/acks` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | critical-contracts.md（原69条详设范围） |
| 57 | `POST /agent/runtime/v1/enroll` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | critical-contracts.md（原69条详设范围） |
| 58 | `POST /agent/runtime/v1/heartbeat` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | critical-contracts.md（原69条详设范围） |
| 59 | `POST /agent/runtime/v1/installations` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | critical-contracts.md（原69条详设范围） |
| 60 | `GET /agent/runtime/v1/installations/{installationId}` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | critical-contracts.md（原69条详设范围） |
| 61 | `POST /agent/runtime/v1/installations/{installationId}/revoke` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | critical-contracts.md（原69条详设范围） |
| 62 | `POST /agent/runtime/v1/session` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | critical-contracts.md（原69条详设范围） |

### AgentSceneController（3条）

| 序号 | 当前method/path | 目标method/path | 响应/边界 | 源码消费关系 | 已有详设/结构证据 |
| ---: | --- | --- | --- | --- | --- |
| 63 | `GET /agent/scenes/{sceneId}/events` | 不变 | SSE；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 64 | `POST /agent/scenes/{sceneId}/phases` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 65 | `GET /agent/scenes/{sceneId}/snapshot` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |

### AgentTaskArtifactController（2条）

| 序号 | 当前method/path | 目标method/path | 响应/边界 | 源码消费关系 | 已有详设/结构证据 |
| ---: | --- | --- | --- | --- | --- |
| 66 | `POST /agent/tasks/{taskId}/artifacts` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 67 | `GET /agent/tasks/{taskId}/artifacts/{artifactId}/versions/{artifactVersion}/content` | 不变 | BINARY；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |

### AgentTaskArtifactOutcomeController（2条）

| 序号 | 当前method/path | 目标method/path | 响应/边界 | 源码消费关系 | 已有详设/结构证据 |
| ---: | --- | --- | --- | --- | --- |
| 68 | `POST /agent/tasks/{taskId}/artifact-outcomes/accept` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 69 | `GET /agent/tasks/{taskId}/artifact-outcomes/accepted` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |

### AgentTaskCancellationController（1条）

| 序号 | 当前method/path | 目标method/path | 响应/边界 | 源码消费关系 | 已有详设/结构证据 |
| ---: | --- | --- | --- | --- | --- |
| 70 | `POST /agent/tasks/{taskId}/cancel` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |

### AgentTaskContextPackController（1条）

| 序号 | 当前method/path | 目标method/path | 响应/边界 | 源码消费关系 | 已有详设/结构证据 |
| ---: | --- | --- | --- | --- | --- |
| 71 | `GET /agent/tasks/{taskId}/context-pack` | 不变 | JSON_BYTES；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |

### AgentTaskCreationOperationController（4条）

| 序号 | 当前method/path | 目标method/path | 响应/边界 | 源码消费关系 | 已有详设/结构证据 |
| ---: | --- | --- | --- | --- | --- |
| 72 | `POST /agent/tasks/creation-operations` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 73 | `GET /agent/tasks/creation-operations/request` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 74 | `POST /agent/tasks/creation-operations/v2` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 75 | `GET /agent/tasks/creation-operations/v2/request` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |

### AgentTaskDeliberationOperationController（3条）

| 序号 | 当前method/path | 目标method/path | 响应/边界 | 源码消费关系 | 已有详设/结构证据 |
| ---: | --- | --- | --- | --- | --- |
| 76 | `GET /agent/tasks/{taskId}/assignment-operation` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 77 | `POST /agent/tasks/{taskId}/point-and-deliberate` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 78 | `GET /agent/tasks/{taskId}/point-and-deliberate/request` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |

### AgentTaskDeliverableController（4条）

| 序号 | 当前method/path | 目标method/path | 响应/边界 | 源码消费关系 | 已有详设/结构证据 |
| ---: | --- | --- | --- | --- | --- |
| 79 | `GET /agent/tasks/{taskId}/deliverables` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 80 | `GET /agent/tasks/{taskId}/deliverables/{artifactId}/versions/{artifactVersion}/content` | 不变 | BINARY；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 81 | `GET /agent/tasks/{taskId}/deliverables/{artifactId}/versions/{artifactVersion}/preview` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 82 | `GET /agent/tasks/{taskId}/deliverables/{artifactId}/versions/{artifactVersion}/preview/parts/{partId}` | 不变 | BINARY；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |

### AgentTaskEventStreamController（1条）

| 序号 | 当前method/path | 目标method/path | 响应/边界 | 源码消费关系 | 已有详设/结构证据 |
| ---: | --- | --- | --- | --- | --- |
| 83 | `GET /agent/tasks/{taskId}/events` | 不变 | SSE；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |

### AgentTaskFormalDeliveryController（4条）

| 序号 | 当前method/path | 目标method/path | 响应/边界 | 源码消费关系 | 已有详设/结构证据 |
| ---: | --- | --- | --- | --- | --- |
| 84 | `GET /agent/tasks/{taskId}/formal-deliveries` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 85 | `POST /agent/tasks/{taskId}/formal-deliveries` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 86 | `POST /agent/tasks/{taskId}/formal-deliveries/{deliveryId}/decision` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 87 | `POST /agent/tasks/{taskId}/formal-deliveries/{deliveryId}/rework-executions` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |

### AgentTaskProviderCostConsentController（2条）

| 序号 | 当前method/path | 目标method/path | 响应/边界 | 源码消费关系 | 已有详设/结构证据 |
| ---: | --- | --- | --- | --- | --- |
| 88 | `GET /agent/tasks/{taskId}/point-and-start-cost-consents/request` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有2条源码关系；未证明全部调用覆盖 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 89 | `GET /agent/tasks/{taskId}/point-and-start-cost-consents/{consentId}` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |

### AgentTaskRequirementSnapshotController（1条）

| 序号 | 当前method/path | 目标method/path | 响应/边界 | 源码消费关系 | 已有详设/结构证据 |
| ---: | --- | --- | --- | --- | --- |
| 90 | `GET /agent/tasks/{taskId}/requirements/current` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |

### AgentTaskWorkspaceController（1条）

| 序号 | 当前method/path | 目标method/path | 响应/边界 | 源码消费关系 | 已有详设/结构证据 |
| ---: | --- | --- | --- | --- | --- |
| 91 | `GET /agent/tasks/{taskId}/workspace` | 不变 | JSON_BYTES；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |

### AgentWorkItemPlanController（2条）

| 序号 | 当前method/path | 目标method/path | 响应/边界 | 源码消费关系 | 已有详设/结构证据 |
| ---: | --- | --- | --- | --- | --- |
| 92 | `POST /agent/tasks/{taskId}/work-item-plans/confirm` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 93 | `POST /agent/tasks/{taskId}/work-item-plans/suggest` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |

### AgentWorkItemReassignmentController（4条）

| 序号 | 当前method/path | 目标method/path | 响应/边界 | 源码消费关系 | 已有详设/结构证据 |
| ---: | --- | --- | --- | --- | --- |
| 94 | `POST /agent/tasks/{taskId}/work-items/{workItemId}/reassignments` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 95 | `POST /agent/tasks/{taskId}/work-items/{workItemId}/reassignments/{reassignmentId}/lease` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 96 | `POST /agent/tasks/{taskId}/work-items/{workItemId}/reassignments/{reassignmentId}/lease/heartbeat` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 97 | `POST /agent/tasks/{taskId}/work-items/{workItemId}/reassignments/{reassignmentId}/lease/start` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |

### AgentWorkItemRuntimeResultController（3条）

| 序号 | 当前method/path | 目标method/path | 响应/边界 | 源码消费关系 | 已有详设/结构证据 |
| ---: | --- | --- | --- | --- | --- |
| 98 | `GET /internal/agent/tasks/{taskId}/work-items/{workItemId}/reassignments/{reassignmentId}/commands/{commandId}/lease` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 99 | `GET /internal/agent/tasks/{taskId}/work-items/{workItemId}/reassignments/{reassignmentId}/commands/{commandId}/result-commit` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 100 | `POST /internal/agent/tasks/{taskId}/work-items/{workItemId}/reassignments/{reassignmentId}/commands/{commandId}/result-commit` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |

### ControlledImagePointAndStartController（1条）

| 序号 | 当前method/path | 目标method/path | 响应/边界 | 源码消费关系 | 已有详设/结构证据 |
| ---: | --- | --- | --- | --- | --- |
| 101 | `GET /agent/tasks/{taskId}/point-and-start-controlled-image/request` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |

### ConversationDeliverableController（1条）

| 序号 | 当前method/path | 目标method/path | 响应/边界 | 源码消费关系 | 已有详设/结构证据 |
| ---: | --- | --- | --- | --- | --- |
| 102 | `GET /agent/conversations/{conversationId}/deliverables` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |

### EconomyReadOnlyPreviewController（9条）

| 序号 | 当前method/path | 目标method/path | 响应/边界 | 源码消费关系 | 已有详设/结构证据 |
| ---: | --- | --- | --- | --- | --- |
| 103 | `GET /economy/preview/agents/{agentId}/hosting-lease` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | critical-contracts.md#12-13；target-finance-contracts.json |
| 104 | `GET /economy/preview/agents/{agentId}/skills` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | critical-contracts.md#12-13；target-finance-contracts.json |
| 105 | `POST /economy/preview/bounty-estimates` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | critical-contracts.md#12-13；target-finance-contracts.json |
| 106 | `GET /economy/preview/capabilities` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | critical-contracts.md#12-13；target-finance-contracts.json |
| 107 | `GET /economy/preview/hosting-plan` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | critical-contracts.md#12-13；target-finance-contracts.json |
| 108 | `GET /economy/preview/ledger` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | critical-contracts.md#12-13；target-finance-contracts.json |
| 109 | `GET /economy/preview/skill-products` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | critical-contracts.md#12-13；target-finance-contracts.json |
| 110 | `GET /economy/preview/skill-products/{productId}` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | critical-contracts.md#12-13；target-finance-contracts.json |
| 111 | `GET /economy/preview/wallet` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | critical-contracts.md#12-13；target-finance-contracts.json |

### HallPrivateMarkController（2条）

| 序号 | 当前method/path | 目标method/path | 响应/边界 | 源码消费关系 | 已有详设/结构证据 |
| ---: | --- | --- | --- | --- | --- |
| 112 | `GET /agent/hall/items/{sourceType}/{sourceId}/mark` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 113 | `PATCH /agent/hall/items/{sourceType}/{sourceId}/mark` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |

### HallReadController（2条）

| 序号 | 当前method/path | 目标method/path | 响应/边界 | 源码消费关系 | 已有详设/结构证据 |
| ---: | --- | --- | --- | --- | --- |
| 114 | `GET /agent/hall/items` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 115 | `GET /agent/hall/overview` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |

### HallRequestDraftController（6条）

| 序号 | 当前method/path | 目标method/path | 响应/边界 | 源码消费关系 | 已有详设/结构证据 |
| ---: | --- | --- | --- | --- | --- |
| 116 | `GET /agent/hall/drafts` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 117 | `POST /agent/hall/drafts` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 118 | `GET /agent/hall/drafts/{draftId}` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 119 | `PUT /agent/hall/drafts/{draftId}` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 120 | `POST /agent/hall/drafts/{draftId}/discard` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 121 | `POST /agent/hall/drafts/{draftId}/submit` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |

### HallSubmissionController（3条）

| 序号 | 当前method/path | 目标method/path | 响应/边界 | 源码消费关系 | 已有详设/结构证据 |
| ---: | --- | --- | --- | --- | --- |
| 122 | `GET /agent/hall/cases/{caseId}` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 123 | `GET /agent/hall/executions/{executionId}/results` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 124 | `GET /agent/hall/submissions/request` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |

### PersonalWorkspaceController（13条）

| 序号 | 当前method/path | 目标method/path | 响应/边界 | 源码消费关系 | 已有详设/结构证据 |
| ---: | --- | --- | --- | --- | --- |
| 125 | `GET /agent/personal-workspace/files` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | critical-contracts.md（原69条详设范围） |
| 126 | `POST /agent/personal-workspace/files` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | critical-contracts.md（原69条详设范围） |
| 127 | `GET /agent/personal-workspace/files/{fileId}` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | critical-contracts.md（原69条详设范围） |
| 128 | `PATCH /agent/personal-workspace/files/{fileId}` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | critical-contracts.md（原69条详设范围） |
| 129 | `POST /agent/personal-workspace/files/{fileId}/restore` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | critical-contracts.md（原69条详设范围） |
| 130 | `POST /agent/personal-workspace/files/{fileId}/trash` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | critical-contracts.md（原69条详设范围） |
| 131 | `GET /agent/personal-workspace/files/{fileId}/usage` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | critical-contracts.md（原69条详设范围） |
| 132 | `GET /agent/personal-workspace/files/{fileId}/versions` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | critical-contracts.md（原69条详设范围） |
| 133 | `POST /agent/personal-workspace/files/{fileId}/versions` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | critical-contracts.md（原69条详设范围） |
| 134 | `GET /agent/personal-workspace/files/{fileId}/versions/{version}/content` | 不变 | BINARY；按自有/特殊响应契约核定 | 已有2条源码关系；未证明全部调用覆盖 | critical-contracts.md（原69条详设范围） |
| 135 | `GET /agent/personal-workspace/files/{fileId}/versions/{version}/preview` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | critical-contracts.md（原69条详设范围） |
| 136 | `GET /agent/personal-workspace/files/{fileId}/versions/{version}/preview/parts/{partId}` | 不变 | BINARY；按自有/特殊响应契约核定 | 已有2条源码关系；未证明全部调用覆盖 | critical-contracts.md（原69条详设范围） |
| 137 | `GET /agent/personal-workspace/operations/{operationId}` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | critical-contracts.md（原69条详设范围） |

### PersonalWorkspaceConversationFileLinkController（3条）

| 序号 | 当前method/path | 目标method/path | 响应/边界 | 源码消费关系 | 已有详设/结构证据 |
| ---: | --- | --- | --- | --- | --- |
| 138 | `GET /agent/conversations/{conversationId}/file-links` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | critical-contracts.md（原69条详设范围）; critical-contracts.md §15（消费适配） |
| 139 | `POST /agent/conversations/{conversationId}/file-links` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | critical-contracts.md（原69条详设范围）; critical-contracts.md §15（消费适配） |
| 140 | `DELETE /agent/conversations/{conversationId}/file-links/{relationId}` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | critical-contracts.md（原69条详设范围）; critical-contracts.md §15（消费适配） |

### PersonalWorkspaceConversationRuntimeController（13条）

| 序号 | 当前method/path | 目标method/path | 响应/边界 | 源码消费关系 | 已有详设/结构证据 |
| ---: | --- | --- | --- | --- | --- |
| 141 | `GET /internal/agent/tasks/conversation-executions/commands` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | critical-contracts.md（原69条详设范围） |
| 142 | `GET /internal/agent/tasks/conversation-executions/controlled-image-v3-commands` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | critical-contracts.md（原69条详设范围） |
| 143 | `POST /internal/agent/tasks/{taskId}/runs/{runId}/conversation/effects/{effectId}/result` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | critical-contracts.md（原69条详设范围） |
| 144 | `POST /internal/agent/tasks/{taskId}/runs/{runId}/conversation/effects/{effectId}/start` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | critical-contracts.md（原69条详设范围） |
| 145 | `POST /internal/agent/tasks/{taskId}/runs/{runId}/conversation/failure` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | critical-contracts.md（原69条详设范围） |
| 146 | `POST /internal/agent/tasks/{taskId}/runs/{runId}/conversation/inputs` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | critical-contracts.md（原69条详设范围） |
| 147 | `POST /internal/agent/tasks/{taskId}/runs/{runId}/conversation/inputs/{inputRef}/content` | 不变 | BINARY；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | critical-contracts.md（原69条详设范围） |
| 148 | `POST /internal/agent/tasks/{taskId}/runs/{runId}/conversation/lease` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | critical-contracts.md（原69条详设范围） |
| 149 | `POST /internal/agent/tasks/{taskId}/runs/{runId}/conversation/lease/renew` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | critical-contracts.md（原69条详设范围） |
| 150 | `POST /internal/agent/tasks/{taskId}/runs/{runId}/conversation/output-commits/{manifestId}` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | critical-contracts.md（原69条详设范围） |
| 151 | `POST /internal/agent/tasks/{taskId}/runs/{runId}/conversation/outputs/{outputId}/content` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | critical-contracts.md（原69条详设范围） |
| 152 | `POST /internal/agent/tasks/{taskId}/runs/{runId}/conversation/result-commits/{manifestId}` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | critical-contracts.md（原69条详设范围） |
| 153 | `POST /internal/agent/tasks/{taskId}/runs/{runId}/conversation/resume` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | critical-contracts.md（原69条详设范围） |

### PersonalWorkspaceExecutionController（6条）

| 序号 | 当前method/path | 目标method/path | 响应/边界 | 源码消费关系 | 已有详设/结构证据 |
| ---: | --- | --- | --- | --- | --- |
| 154 | `GET /agent/personal-workspace/executions` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有2条源码关系；未证明全部调用覆盖 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 155 | `POST /agent/personal-workspace/executions` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 156 | `GET /agent/personal-workspace/executions/capabilities` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有2条源码关系；未证明全部调用覆盖 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 157 | `GET /agent/personal-workspace/executions/request` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 158 | `GET /agent/personal-workspace/executions/{executionId}` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有2条源码关系；未证明全部调用覆盖 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 159 | `POST /agent/personal-workspace/executions/{executionId}/revoke-inputs` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |

### PersonalWorkspaceRuntimeFileController（7条）

| 序号 | 当前method/path | 目标method/path | 响应/边界 | 源码消费关系 | 已有详设/结构证据 |
| ---: | --- | --- | --- | --- | --- |
| 160 | `GET /internal/agent/tasks/workspace-executions/commands` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | critical-contracts.md（原69条详设范围） |
| 161 | `POST /internal/agent/tasks/{taskId}/runs/{runId}/failure` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | critical-contracts.md（原69条详设范围） |
| 162 | `GET /internal/agent/tasks/{taskId}/runs/{runId}/inputs` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | critical-contracts.md（原69条详设范围） |
| 163 | `GET /internal/agent/tasks/{taskId}/runs/{runId}/inputs/{inputRef}/content` | 不变 | BINARY；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | critical-contracts.md（原69条详设范围） |
| 164 | `POST /internal/agent/tasks/{taskId}/runs/{runId}/output-commits/{manifestId}` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | critical-contracts.md（原69条详设范围） |
| 165 | `POST /internal/agent/tasks/{taskId}/runs/{runId}/outputs/{outputId}/content` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | critical-contracts.md（原69条详设范围） |
| 166 | `POST /internal/agent/tasks/{taskId}/runs/{runId}/start` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | critical-contracts.md（原69条详设范围） |

### PersonalWorkspaceTaskFileLinkController（3条）

| 序号 | 当前method/path | 目标method/path | 响应/边界 | 源码消费关系 | 已有详设/结构证据 |
| ---: | --- | --- | --- | --- | --- |
| 167 | `GET /agent/tasks/{taskId}/file-links` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | critical-contracts.md（原69条详设范围）; critical-contracts.md §15（消费适配） |
| 168 | `POST /agent/tasks/{taskId}/file-links` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | critical-contracts.md（原69条详设范围）; critical-contracts.md §15（消费适配） |
| 169 | `DELETE /agent/tasks/{taskId}/file-links/{relationId}` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | critical-contracts.md（原69条详设范围）; critical-contracts.md §15（消费适配） |

### SkillManagedCredentialController（1条）

| 序号 | 当前method/path | 目标method/path | 响应/边界 | 源码消费关系 | 已有详设/结构证据 |
| ---: | --- | --- | --- | --- | --- |
| 170 | `POST /agent/{agentId}/skill-install-credentials` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | critical-contracts.md（原69条详设范围） |

### SkillMarketplaceController（7条）

| 序号 | 当前method/path | 目标method/path | 响应/边界 | 源码消费关系 | 已有详设/结构证据 |
| ---: | --- | --- | --- | --- | --- |
| 171 | `POST /agent/skill-orders` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | critical-contracts.md（原69条详设范围）; critical-contracts.md#12-13；target-finance-contracts.json |
| 172 | `POST /agent/skill-orders/quotes` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | critical-contracts.md（原69条详设范围）; critical-contracts.md#12-13；target-finance-contracts.json |
| 173 | `GET /agent/skill-orders/{orderId}` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | critical-contracts.md（原69条详设范围）; critical-contracts.md#12-13；target-finance-contracts.json |
| 174 | `GET /agent/skill-products` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | critical-contracts.md（原69条详设范围）; critical-contracts.md#12-13；target-finance-contracts.json |
| 175 | `GET /agent/skill-products/{productId}` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | critical-contracts.md（原69条详设范围）; critical-contracts.md#12-13；target-finance-contracts.json |
| 176 | `GET /agent/{agentId}/skill-entitlements` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | critical-contracts.md（原69条详设范围）; critical-contracts.md#12-13；target-finance-contracts.json |
| 177 | `GET /economy/capabilities` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | critical-contracts.md（原69条详设范围）; critical-contracts.md#12-13；target-finance-contracts.json |

### SkillPackageController（1条）

| 序号 | 当前method/path | 目标method/path | 响应/边界 | 源码消费关系 | 已有详设/结构证据 |
| ---: | --- | --- | --- | --- | --- |
| 178 | `GET /internal/agent/skill-installations/{installationId}/package` | 不变 | BINARY；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | critical-contracts.md（原69条详设范围） |

### SkillPackagePublicationController（1条）

| 序号 | 当前method/path | 目标method/path | 响应/边界 | 源码消费关系 | 已有详设/结构证据 |
| ---: | --- | --- | --- | --- | --- |
| 179 | `POST /agent/skill-maintenance/product-versions` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | critical-contracts.md（原69条详设范围） |

## base

### DictController（7条）

| 序号 | 当前method/path | 目标method/path | 响应/边界 | 源码消费关系 | 已有详设/结构证据 |
| ---: | --- | --- | --- | --- | --- |
| 180 | `GET /dict/cleanCache` | `POST /dict/cleanCache` | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | ordinary-contracts-review5.md |
| 181 | `POST /dict/create` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | ordinary-contracts-review5.md |
| 182 | `DELETE /dict/delete` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | ordinary-contracts-review5.md |
| 183 | `GET /dict/get` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | ordinary-contracts-review5.md |
| 184 | `POST /dict/list` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | ordinary-contracts-review5.md |
| 185 | `GET /dict/locale` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | ordinary-contracts-review5.md |
| 186 | `POST /dict/update` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | ordinary-contracts-review5.md |

## chat

### ActiveAgentController（1条）

| 序号 | 当前method/path | 目标method/path | 响应/边界 | 源码消费关系 | 已有详设/结构证据 |
| ---: | --- | --- | --- | --- | --- |
| 187 | `GET /agent/active` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |

### AgentInspectionInputController（1条）

| 序号 | 当前method/path | 目标method/path | 响应/边界 | 源码消费关系 | 已有详设/结构证据 |
| ---: | --- | --- | --- | --- | --- |
| 188 | `GET /internal/agent/chat/requests/{requestId}/turns/{turnId}/inspection/inputs/{sourceRefId}/content` | 不变 | BINARY；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |

### AgentMaterialsInputController（1条）

| 序号 | 当前method/path | 目标method/path | 响应/边界 | 源码消费关系 | 已有详设/结构证据 |
| ---: | --- | --- | --- | --- | --- |
| 189 | `GET /internal/agent/chat/requests/{requestId}/turns/{turnId}/materials/inputs/{sourceRefId}/content` | 不变 | BINARY；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | critical-contracts.md（原69条详设范围） |

### AgentTaskControlledImageCapabilityController（1条）

| 序号 | 当前method/path | 目标method/path | 响应/边界 | 源码消费关系 | 已有详设/结构证据 |
| ---: | --- | --- | --- | --- | --- |
| 190 | `GET /agent/tasks/{taskId}/point-and-start-controlled-image-capability` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |

### AgentTaskPointAndStartCapabilityController（1条）

| 序号 | 当前method/path | 目标method/path | 响应/边界 | 源码消费关系 | 已有详设/结构证据 |
| ---: | --- | --- | --- | --- | --- |
| 191 | `GET /agent/tasks/{taskId}/point-and-start-capability` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |

### AgentTaskSelectedOutputFinalizationController（3条）

| 序号 | 当前method/path | 目标method/path | 响应/边界 | 源码消费关系 | 已有详设/结构证据 |
| ---: | --- | --- | --- | --- | --- |
| 192 | `POST /agent/tasks/{taskId}/finalizations` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | critical-contracts.md#14 |
| 193 | `GET /agent/tasks/{taskId}/finalizations/request` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | critical-contracts.md#14 |
| 194 | `GET /agent/tasks/{taskId}/finalizations/{operationId}` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | critical-contracts.md#14 |

### AgentTaskThreadController（4条）

| 序号 | 当前method/path | 目标method/path | 响应/边界 | 源码消费关系 | 已有详设/结构证据 |
| ---: | --- | --- | --- | --- | --- |
| 195 | `GET /chat/task-threads/{taskId}/team` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 196 | `POST /chat/task-threads/{taskId}/team` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 197 | `GET /chat/task-threads/{taskId}/team/messages` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 198 | `POST /chat/task-threads/{taskId}/team/messages` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |

### ArchiveController（3条）

| 序号 | 当前method/path | 目标method/path | 响应/边界 | 源码消费关系 | 已有详设/结构证据 |
| ---: | --- | --- | --- | --- | --- |
| 199 | `GET /archive/v1/catalog` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 200 | `GET /archive/v1/editions/{editionId}/chapters/{chapterId}` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 201 | `GET /archive/v1/editions/{editionId}/preface` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |

### ArchivePersonalDataController（8条）

| 序号 | 当前method/path | 目标method/path | 响应/边界 | 源码消费关系 | 已有详设/结构证据 |
| ---: | --- | --- | --- | --- | --- |
| 202 | `GET /archive/v1/me/bookmarks` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 203 | `DELETE /archive/v1/me/bookmarks/{bookmarkId}` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 204 | `PUT /archive/v1/me/bookmarks/{bookmarkId}` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 205 | `GET /archive/v1/me/notes` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 206 | `DELETE /archive/v1/me/notes/{noteId}` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 207 | `PUT /archive/v1/me/notes/{noteId}` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 208 | `GET /archive/v1/me/progress/{editionId}` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 209 | `PUT /archive/v1/me/progress/{editionId}` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |

### ArchiveQuestionController（4条）

| 序号 | 当前method/path | 目标method/path | 响应/边界 | 源码消费关系 | 已有详设/结构证据 |
| ---: | --- | --- | --- | --- | --- |
| 210 | `GET /archive/v1/me/questions/{questionId}` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 211 | `PUT /archive/v1/me/questions/{questionId}` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 212 | `GET /archive/v1/me/questions/{questionId}/events` | 不变 | SSE；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 213 | `POST /archive/v1/me/questions/{questionId}/retry` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |

### ChatBountyExecutionTerminationController（2条）

| 序号 | 当前method/path | 目标method/path | 响应/边界 | 源码消费关系 | 已有详设/结构证据 |
| ---: | --- | --- | --- | --- | --- |
| 214 | `GET /chat/conversations/{conversationId}/requests/{requestId}/abandon-execution` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 215 | `POST /chat/conversations/{conversationId}/requests/{requestId}/abandon-execution` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |

### ChatBountyInteractionController（1条）

| 序号 | 当前method/path | 目标method/path | 响应/边界 | 源码消费关系 | 已有详设/结构证据 |
| ---: | --- | --- | --- | --- | --- |
| 216 | `POST /chat/conversations/{conversationId}/interactions` | 不变 | RETIRED_410；已退役410：保持拒绝，不恢复执行 | 已有1条源码关系；未证明全部调用覆盖 | critical-contracts.md（原69条详设范围） |

### ChatBountyInteractionV3Controller（6条）

| 序号 | 当前method/path | 目标method/path | 响应/边界 | 源码消费关系 | 已有详设/结构证据 |
| ---: | --- | --- | --- | --- | --- |
| 217 | `GET /chat/conversations/{conversationId}/interactions/context` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有2条源码关系；未证明全部调用覆盖 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 218 | `POST /chat/conversations/{conversationId}/interactions/preview` | 不变 | RETIRED_410；已退役410：保持拒绝，不恢复执行 | 已有1条源码关系；未证明全部调用覆盖 | critical-contracts.md（原69条详设范围） |
| 219 | `POST /chat/conversations/{conversationId}/interactions/provider-consents` | 不变 | RETIRED_410；已退役410：保持拒绝，不恢复执行 | 已有1条源码关系；未证明全部调用覆盖 | critical-contracts.md（原69条详设范围） |
| 220 | `GET /chat/conversations/{conversationId}/interactions/provider-consents/request` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 221 | `POST /chat/conversations/{conversationId}/interactions/provider-consents/{consentId}/revoke` | 不变 | RETIRED_410；已退役410：保持拒绝，不恢复执行 | 未建立人工源码关系；不等于无消费者 | critical-contracts.md（原69条详设范围） |
| 222 | `GET /chat/conversations/{conversationId}/interactions/request` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |

### ChatBountyMediaController（3条）

| 序号 | 当前method/path | 目标method/path | 响应/边界 | 源码消费关系 | 已有详设/结构证据 |
| ---: | --- | --- | --- | --- | --- |
| 223 | `GET /chat/requests/{requestId}/steps/{stepId}/outputs` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | critical-contracts.md（原69条详设范围） |
| 224 | `GET /chat/requests/{requestId}/steps/{stepId}/outputs/{outputId}` | 不变 | BINARY；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | critical-contracts.md（原69条详设范围） |
| 225 | `HEAD /chat/requests/{requestId}/steps/{stepId}/outputs/{outputId}` | 不变 | BINARY；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | critical-contracts.md（原69条详设范围） |

### ChatBountyRequestIndexController（1条）

| 序号 | 当前method/path | 目标method/path | 响应/边界 | 源码消费关系 | 已有详设/结构证据 |
| ---: | --- | --- | --- | --- | --- |
| 226 | `GET /chat/conversations/{conversationId}/requests` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |

### ChatController（14条）

| 序号 | 当前method/path | 目标method/path | 响应/边界 | 源码消费关系 | 已有详设/结构证据 |
| ---: | --- | --- | --- | --- | --- |
| 227 | `GET /chat/capabilities` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 228 | `GET /chat/conversation/content` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有2条源码关系；未证明全部调用覆盖 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 229 | `DELETE /chat/conversation/delete` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有2条源码关系；未证明全部调用覆盖 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 230 | `GET /chat/conversation/events` | 不变 | SSE；按自有/特殊响应契约核定 | 已有3条源码关系；未证明全部调用覆盖 | critical-contracts.md §15（SSE消费适配） |
| 231 | `POST /chat/conversation/list` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有3条源码关系；未证明全部调用覆盖 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 232 | `POST /chat/conversation/update` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 233 | `POST /chat/library/documents` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 234 | `POST /chat/library/search` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 235 | `GET /chat/requests/{requestId}` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有3条源码关系；未证明全部调用覆盖 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 236 | `POST /chat/requests/{requestId}/cancel` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 237 | `POST /chat/stop_stream` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 238 | `POST /chat/stream` | 不变 | SSE；按自有/特殊响应契约核定 | 已有2条源码关系；未证明全部调用覆盖 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 239 | `GET /chat/turns/{turnId}` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 240 | `POST /chat/turns/{turnId}/cancel` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |

### ChatConversationArchiveController（2条）

| 序号 | 当前method/path | 目标method/path | 响应/边界 | 源码消费关系 | 已有详设/结构证据 |
| ---: | --- | --- | --- | --- | --- |
| 241 | `POST /chat/conversations/{conversationId}/archive-operations` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有2条源码关系；未证明全部调用覆盖 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 242 | `GET /chat/conversations/{conversationId}/archive-operations/{operationId}` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |

### ChatConversationAssetController（3条）

| 序号 | 当前method/path | 目标method/path | 响应/边界 | 源码消费关系 | 已有详设/结构证据 |
| ---: | --- | --- | --- | --- | --- |
| 243 | `GET /chat/conversations/{conversationId}/assets/{assetId}` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 244 | `GET /chat/conversations/{conversationId}/assets/{assetId}/content` | 不变 | BINARY；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 245 | `HEAD /chat/conversations/{conversationId}/assets/{assetId}/content` | 不变 | BINARY；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |

### ChatSkillResultController（1条）

| 序号 | 当前method/path | 目标method/path | 响应/边界 | 源码消费关系 | 已有详设/结构证据 |
| ---: | --- | --- | --- | --- | --- |
| 246 | `GET /chat/conversations/{conversationId}/executions/{executionId}/result` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | critical-contracts.md（原69条详设范围） |

### ChatTypedDeliberationController（2条）

| 序号 | 当前method/path | 目标method/path | 响应/边界 | 源码消费关系 | 已有详设/结构证据 |
| ---: | --- | --- | --- | --- | --- |
| 247 | `POST /chat/conversations/{conversationId}/interactions/discussion` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | critical-contracts.md（原69条详设范围） |
| 248 | `GET /chat/conversations/{conversationId}/requests/{requestId}/typed-outcome` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有3条源码关系；未证明全部调用覆盖 | critical-contracts.md（原69条详设范围） |

### ChatTypedInspectionController（3条）

| 序号 | 当前method/path | 目标method/path | 响应/边界 | 源码消费关系 | 已有详设/结构证据 |
| ---: | --- | --- | --- | --- | --- |
| 249 | `POST /chat/conversations/{conversationId}/interactions/inspection` | 不变 | RETIRED_410；已退役410：保持拒绝，不恢复执行 | 已有1条源码关系；未证明全部调用覆盖 | critical-contracts.md（原69条详设范围） |
| 250 | `GET /chat/conversations/{conversationId}/interactions/inspection/request` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 251 | `GET /chat/conversations/{conversationId}/requests/{requestId}/inspection-outcome` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |

### JuyitingActionController（2条）

| 序号 | 当前method/path | 目标method/path | 响应/边界 | 源码消费关系 | 已有详设/结构证据 |
| ---: | --- | --- | --- | --- | --- |
| 252 | `POST /juyiting/actions/{intentId}/dispatch` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 253 | `GET /juyiting/agents/{agentId}/mailbox` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |

### SpeechSynthesisController（1条）

| 序号 | 当前method/path | 目标method/path | 响应/边界 | 源码消费关系 | 已有详设/结构证据 |
| ---: | --- | --- | --- | --- | --- |
| 254 | `POST /chat/speech/synthesis` | 不变 | BINARY；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |

### SpeechTranscriptionController（1条）

| 序号 | 当前method/path | 目标method/path | 响应/边界 | 源码消费关系 | 已有详设/结构证据 |
| ---: | --- | --- | --- | --- | --- |
| 255 | `POST /chat/speech/transcriptions` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |

### TaskSkillCapabilityController（1条）

| 序号 | 当前method/path | 目标method/path | 响应/边界 | 源码消费关系 | 已有详设/结构证据 |
| ---: | --- | --- | --- | --- | --- |
| 256 | `GET /agent/tasks/{taskId}/skill-capabilities` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | critical-contracts.md（原69条详设范围） |

## dwz

### DwzController（7条）

| 序号 | 当前method/path | 目标method/path | 响应/边界 | 源码消费关系 | 已有详设/结构证据 |
| ---: | --- | --- | --- | --- | --- |
| 257 | `DELETE /dwz/delete` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | ordinary-contracts-review5.md |
| 258 | `POST /dwz/gen` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | ordinary-contracts-review5.md |
| 259 | `GET /dwz/get` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | ordinary-contracts-review5.md |
| 260 | `POST /dwz/list` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | ordinary-contracts-review5.md |
| 261 | `GET /dwz/restore` | `POST /dwz/restore` | SELF_JSON；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | ordinary-contracts-review5.md |
| 262 | `POST /dwz/update` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | ordinary-contracts-review5.md |
| 263 | `GET /dwz/view/{uri:.+}/**` | 不变 | HTML_OR_REDIRECT；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | ordinary-contracts-review5.md |

## economy

### EconomyWalletController（4条）

| 序号 | 当前method/path | 目标method/path | 响应/边界 | 源码消费关系 | 已有详设/结构证据 |
| ---: | --- | --- | --- | --- | --- |
| 264 | `GET /economy/ledger` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 265 | `POST /economy/onboarding-grant` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 266 | `POST /economy/preview/issuances` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 267 | `GET /economy/wallet` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |

## isp

### CarController（20条）

| 序号 | 当前method/path | 目标method/path | 响应/边界 | 源码消费关系 | 已有详设/结构证据 |
| ---: | --- | --- | --- | --- | --- |
| 268 | `POST /car/brand/create` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 269 | `GET /car/brand/delete` | `DELETE /car/brand/delete` | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 270 | `GET /car/brand/get` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 271 | `POST /car/brand/update` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 272 | `POST /car/brandAudi/create` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 273 | `GET /car/brandAudi/delete` | `DELETE /car/brandAudi/delete` | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 274 | `GET /car/brandAudi/get` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 275 | `POST /car/brandAudi/update` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 276 | `POST /car/brandMf/create` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 277 | `GET /car/brandMf/delete` | `DELETE /car/brandMf/delete` | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 278 | `GET /car/brandMf/get` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 279 | `POST /car/brandMf/update` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 280 | `POST /car/brandVersion/create` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 281 | `GET /car/brandVersion/delete` | `DELETE /car/brandVersion/delete` | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 282 | `GET /car/brandVersion/get` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 283 | `POST /car/brandVersion/update` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 284 | `POST /car/list/brand` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 285 | `POST /car/list/brandAudi` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 286 | `POST /car/list/brandMf` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 287 | `POST /car/list/brandVersion` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |

### CmsController（17条）

| 序号 | 当前method/path | 目标method/path | 响应/边界 | 源码消费关系 | 已有详设/结构证据 |
| ---: | --- | --- | --- | --- | --- |
| 288 | `POST /cms/column/create` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 289 | `GET /cms/column/delete` | `DELETE /cms/column/delete` | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 290 | `GET /cms/column/get` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 291 | `POST /cms/column/list` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 292 | `POST /cms/column/update` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 293 | `GET /cms/config/get` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 294 | `POST /cms/config/update` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 295 | `POST /cms/row/create` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 296 | `POST /cms/row/delete` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 297 | `POST /cms/row/get` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 298 | `POST /cms/row/list` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 299 | `POST /cms/row/update` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 300 | `POST /cms/table/create` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 301 | `GET /cms/table/delete` | `DELETE /cms/table/delete` | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 302 | `GET /cms/table/get` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 303 | `POST /cms/table/list` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 304 | `POST /cms/table/update` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |

### FileController（1条）

| 序号 | 当前method/path | 目标method/path | 响应/边界 | 源码消费关系 | 已有详设/结构证据 |
| ---: | --- | --- | --- | --- | --- |
| 305 | `GET /file/res/{uri:.+}/**` | 不变 | BINARY；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |

### IspController（9条）

| 序号 | 当前method/path | 目标method/path | 响应/边界 | 源码消费关系 | 已有详设/结构证据 |
| ---: | --- | --- | --- | --- | --- |
| 306 | `GET /isp/dns/hosts` | 不变 | TEXT_FILE；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 307 | `ANY /isp/dns/update_record` | `POST /isp/dns/update_record` | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 308 | `GET /isp/domain/record/delete` | `DELETE /isp/domain/record/delete` | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 309 | `POST /isp/server/create` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 310 | `GET /isp/server/delete` | `DELETE /isp/server/delete` | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 311 | `GET /isp/server/get` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 312 | `POST /isp/server/list` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 313 | `GET /isp/server/ssl/refresh` | `POST /isp/server/ssl/refresh` | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 314 | `POST /isp/server/update` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |

### LdapController（14条）

| 序号 | 当前method/path | 目标method/path | 响应/边界 | 源码消费关系 | 已有详设/结构证据 |
| ---: | --- | --- | --- | --- | --- |
| 315 | `GET /ldap/account/findByUid` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 316 | `POST /ldap/user/create` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 317 | `GET /ldap/user/delete` | `DELETE /ldap/user/delete` | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 318 | `GET /ldap/user/findAll` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 319 | `GET /ldap/user/get` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 320 | `POST /ldap/user/search` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 321 | `POST /ldap/user/update` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 322 | `POST /ldap/usergroup/create` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 323 | `GET /ldap/usergroup/delete` | `DELETE /ldap/usergroup/delete` | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 324 | `GET /ldap/usergroup/findAll` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 325 | `GET /ldap/usergroup/findByCn` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 326 | `POST /ldap/usergroup/member/add` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 327 | `POST /ldap/usergroup/member/delete` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 328 | `POST /ldap/usergroup/update` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |

## kefu

### KefuController（11条）

| 序号 | 当前method/path | 目标method/path | 响应/边界 | 源码消费关系 | 已有详设/结构证据 |
| ---: | --- | --- | --- | --- | --- |
| 329 | `POST /kefu/faq/create` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 330 | `GET /kefu/faq/delete` | `DELETE /kefu/faq/delete` | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 331 | `GET /kefu/faq/get` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 332 | `POST /kefu/faq/list` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 333 | `POST /kefu/faq/update` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 334 | `POST /kefu/image/upload` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 335 | `POST /kefu/message/create` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有2条源码关系；未证明全部调用覆盖 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 336 | `GET /kefu/message/delete` | `DELETE /kefu/message/delete` | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 337 | `GET /kefu/message/get` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 338 | `POST /kefu/message/list` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 339 | `POST /kefu/message/update` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |

## material

### MediaController（8条）

| 序号 | 当前method/path | 目标method/path | 响应/边界 | 源码消费关系 | 已有详设/结构证据 |
| ---: | --- | --- | --- | --- | --- |
| 340 | `POST /media/create` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 341 | `GET /media/delete` | `DELETE /media/delete` | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 342 | `GET /media/get` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 343 | `GET /media/get/content` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 344 | `POST /media/list` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 345 | `GET /media/thumbnail` | 不变 | BINARY；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 346 | `POST /media/update` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 347 | `POST /media/upload` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |

### NewsController（6条）

| 序号 | 当前method/path | 目标method/path | 响应/边界 | 源码消费关系 | 已有详设/结构证据 |
| ---: | --- | --- | --- | --- | --- |
| 348 | `POST /news/create` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 349 | `GET /news/delete` | `DELETE /news/delete` | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 350 | `GET /news/get` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 351 | `POST /news/list` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 352 | `GET /news/send` | `POST /news/send` | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 353 | `POST /news/update` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |

### PhraseController（7条）

| 序号 | 当前method/path | 目标method/path | 响应/边界 | 源码消费关系 | 已有详设/结构证据 |
| ---: | --- | --- | --- | --- | --- |
| 354 | `POST /phrase/create` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 355 | `GET /phrase/delete` | `DELETE /phrase/delete` | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 356 | `GET /phrase/get` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 357 | `POST /phrase/get/random` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有2条源码关系；未证明全部调用覆盖 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 358 | `GET /phrase/read` | `POST /phrase/read` | SELF_JSON；按自有/特殊响应契约核定 | 已有2条源码关系；未证明全部调用覆盖 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 359 | `POST /phrase/update` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 360 | `POST /phrase/vote` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |

### PvLogController（5条）

| 序号 | 当前method/path | 目标method/path | 响应/边界 | 源码消费关系 | 已有详设/结构证据 |
| ---: | --- | --- | --- | --- | --- |
| 361 | `POST /pvlog/create` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 362 | `DELETE /pvlog/delete` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 363 | `GET /pvlog/get` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 364 | `POST /pvlog/list` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 365 | `POST /pvlog/update` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |

### TipController（5条）

| 序号 | 当前method/path | 目标method/path | 响应/边界 | 源码消费关系 | 已有详设/结构证据 |
| ---: | --- | --- | --- | --- | --- |
| 366 | `POST /tip/create` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 367 | `GET /tip/delete` | `DELETE /tip/delete` | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 368 | `GET /tip/get` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 369 | `POST /tip/list` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 370 | `POST /tip/update` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |

### VoteController（8条）

| 序号 | 当前method/path | 目标method/path | 响应/边界 | 源码消费关系 | 已有详设/结构证据 |
| ---: | --- | --- | --- | --- | --- |
| 371 | `POST /vote/create` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 372 | `GET /vote/delete` | `DELETE /vote/delete` | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 373 | `GET /vote/get` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 374 | `GET /vote/get/random` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 375 | `POST /vote/get/ticks` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 376 | `POST /vote/list` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 377 | `POST /vote/tick` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 378 | `POST /vote/update` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |

## oauth

### AuthenticationController（1条）

| 序号 | 当前method/path | 目标method/path | 响应/边界 | 源码消费关系 | 已有详设/结构证据 |
| ---: | --- | --- | --- | --- | --- |
| 379 | `GET /token` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |

### OAuthResourceIdentityController（1条）

| 序号 | 当前method/path | 目标method/path | 响应/边界 | 源码消费关系 | 已有详设/结构证据 |
| ---: | --- | --- | --- | --- | --- |
| 380 | `GET /resource` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |

### OauthController（10条）

| 序号 | 当前method/path | 目标method/path | 响应/边界 | 源码消费关系 | 已有详设/结构证据 |
| ---: | --- | --- | --- | --- | --- |
| 381 | `GET /oauth/client/get` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 382 | `POST /oauth/client/update` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 383 | `GET /oauth/clientid` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 384 | `GET /oauth/confirm_access` | 不变 | HTML_OR_REDIRECT；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 385 | `GET /oauth/third-party/autologin` | 不变 | HTML_OR_REDIRECT；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 386 | `GET /oauth/third-party/github` | 不变 | HTML_OR_REDIRECT；第三方外部协议：不改wire，仅核外部边界 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 387 | `GET /oauth/third-party/weibo` | 不变 | HTML_OR_REDIRECT；第三方外部协议：不改wire，仅核外部边界 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 388 | `GET /oauth/third-party/weixin` | 不变 | HTML_OR_REDIRECT；第三方外部协议：不改wire，仅核外部边界 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 389 | `GET /oauth/third-party/wxmp` | 不变 | HTML_OR_REDIRECT；第三方外部协议：不改wire，仅核外部边界 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 390 | `GET /oauth/user` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |

### SessionRevocationController（1条）

| 序号 | 当前method/path | 目标method/path | 响应/边界 | 源码消费关系 | 已有详设/结构证据 |
| ---: | --- | --- | --- | --- | --- |
| 391 | `POST /user/me/sessions/revoke-all` | 不变 | BODYLESS_SUCCESS_JSON_ERRORS；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |

## point

### GiftController（10条）

| 序号 | 当前method/path | 目标method/path | 响应/边界 | 源码消费关系 | 已有详设/结构证据 |
| ---: | --- | --- | --- | --- | --- |
| 392 | `POST /gift/create` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 393 | `DELETE /gift/delete` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 394 | `GET /gift/get` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 395 | `POST /gift/list` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 396 | `POST /gift/update` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 397 | `POST /gift/usage/add` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 398 | `POST /gift/usage/cancel/{giftUsageId}` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 399 | `POST /gift/usage/delete/{giftUsageId}` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 400 | `POST /gift/usage/list/gift/{giftId}` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 401 | `POST /gift/usage/list/user/{user}` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |

### PointController（4条）

| 序号 | 当前method/path | 目标method/path | 响应/边界 | 源码消费关系 | 已有详设/结构证据 |
| ---: | --- | --- | --- | --- | --- |
| 402 | `POST /point/init` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 403 | `POST /point/luck` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 404 | `POST /point/referral` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 405 | `POST /point/sign` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |

## sms

### SmsController（17条）

| 序号 | 当前method/path | 目标method/path | 响应/边界 | 源码消费关系 | 已有详设/结构证据 |
| ---: | --- | --- | --- | --- | --- |
| 406 | `GET /sms/balance` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 407 | `GET /sms/buy` | `POST /sms/buy` | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 408 | `GET /sms/config/get` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 409 | `POST /sms/config/update` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 410 | `GET /sms/gen` | `POST /sms/gen` | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 411 | `GET /sms/receive` | 不变 | EXTERNAL_CALLBACK；第三方外部协议：不改wire，仅核外部边界 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 412 | `POST /sms/reply/list` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 413 | `POST /sms/send` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 414 | `POST /sms/send/list` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 415 | `POST /sms/sendBatch` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 416 | `POST /sms/template/create` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 417 | `GET /sms/template/delete` | `DELETE /sms/template/delete` | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 418 | `GET /sms/template/get` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 419 | `POST /sms/template/list` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 420 | `POST /sms/template/update` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 421 | `GET /sms/use` | `POST /sms/use` | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 422 | `GET /sms/validate` | `POST /sms/validate` | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |

## task

### JobController（5条）

| 序号 | 当前method/path | 目标method/path | 响应/边界 | 源码消费关系 | 已有详设/结构证据 |
| ---: | --- | --- | --- | --- | --- |
| 423 | `POST /job/execute` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 424 | `POST /job/execute/async` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 425 | `GET /job/get` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 426 | `GET /job/list` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 427 | `GET /job/status` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |

### TaskController（7条）

| 序号 | 当前method/path | 目标method/path | 响应/边界 | 源码消费关系 | 已有详设/结构证据 |
| ---: | --- | --- | --- | --- | --- |
| 428 | `DELETE /task/cancel` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 429 | `POST /task/create` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 430 | `DELETE /task/delete` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有2条源码关系；未证明全部调用覆盖 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 431 | `GET /task/get` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 432 | `POST /task/item/search` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 433 | `POST /task/search` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有2条源码关系；未证明全部调用覆盖 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 434 | `POST /task/update` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |

## user

### GroupController（10条）

| 序号 | 当前method/path | 目标method/path | 响应/边界 | 源码消费关系 | 已有详设/结构证据 |
| ---: | --- | --- | --- | --- | --- |
| 435 | `POST /group/create` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 436 | `DELETE /group/delete` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 437 | `GET /group/get` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 438 | `POST /group/get/roles` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 439 | `POST /group/get/users` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 440 | `POST /group/list` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 441 | `POST /group/role/change` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 442 | `POST /group/update` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 443 | `POST /group/users/add` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 444 | `POST /group/users/del` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |

### LoginController（11条）

| 序号 | 当前method/path | 目标method/path | 响应/边界 | 源码消费关系 | 已有详设/结构证据 |
| ---: | --- | --- | --- | --- | --- |
| 445 | `GET /login/index.html` | 不变 | HTML_OR_REDIRECT；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 446 | `GET /login/register` | 不变 | HTML_OR_REDIRECT；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 447 | `POST /login/register` | 不变 | HTML_OR_REDIRECT；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 448 | `GET /login/resetPassword` | 不变 | HTML_OR_REDIRECT；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 449 | `POST /login/resetPassword` | 不变 | HTML_OR_REDIRECT；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 450 | `GET /login/sms/gen` | `POST /login/sms/gen` | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 451 | `GET /login/user` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 452 | `POST /login/user/avatar/update` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 453 | `GET /login/user/info` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 454 | `POST /login/user/info/update` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 455 | `GET /login/user/password/change` | `POST /login/user/password/change` | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |

### MsgController（9条）

| 序号 | 当前method/path | 目标method/path | 响应/边界 | 源码消费关系 | 已有详设/结构证据 |
| ---: | --- | --- | --- | --- | --- |
| 456 | `POST /msg/create` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | ordinary-contracts-review5.md |
| 457 | `DELETE /msg/delete` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | ordinary-contracts-review5.md |
| 458 | `GET /msg/get` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | ordinary-contracts-review5.md |
| 459 | `POST /msg/list` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | ordinary-contracts-review5.md |
| 460 | `GET /msg/read` | `POST /msg/read` | SELF_JSON；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | ordinary-contracts-review5.md |
| 461 | `GET /msg/readall` | `POST /msg/readall` | SELF_JSON；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | ordinary-contracts-review5.md |
| 462 | `GET /msg/recycle` | `POST /msg/recycle` | SELF_JSON；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | ordinary-contracts-review5.md |
| 463 | `GET /msg/restore` | `POST /msg/restore` | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | ordinary-contracts-review5.md |
| 464 | `GET /msg/unread` | `POST /msg/unread` | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | ordinary-contracts-review5.md |

### OrgController（13条）

| 序号 | 当前method/path | 目标method/path | 响应/边界 | 源码消费关系 | 已有详设/结构证据 |
| ---: | --- | --- | --- | --- | --- |
| 465 | `POST /org/create` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 466 | `DELETE /org/delete` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 467 | `GET /org/get` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 468 | `GET /org/get/director` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 469 | `GET /org/get/name` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 470 | `GET /org/get/parent` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 471 | `POST /org/get/users` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 472 | `POST /org/list` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 473 | `POST /org/list/sub` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 474 | `POST /org/update` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 475 | `POST /org/update/logo` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 476 | `POST /org/users/add` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 477 | `POST /org/users/del` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |

### PermsController（6条）

| 序号 | 当前method/path | 目标method/path | 响应/边界 | 源码消费关系 | 已有详设/结构证据 |
| ---: | --- | --- | --- | --- | --- |
| 478 | `POST /action/create` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 479 | `DELETE /action/delete` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 480 | `GET /action/get` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 481 | `POST /action/list` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 482 | `POST /action/refresh` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 483 | `POST /action/update` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |

### RoleController（10条）

| 序号 | 当前method/path | 目标method/path | 响应/边界 | 源码消费关系 | 已有详设/结构证据 |
| ---: | --- | --- | --- | --- | --- |
| 484 | `POST /role/create` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 485 | `DELETE /role/delete` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 486 | `GET /role/get` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 487 | `POST /role/get/perms` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 488 | `POST /role/get/users` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 489 | `POST /role/list` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 490 | `POST /role/perms/change` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 491 | `POST /role/update` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 492 | `POST /role/users/add` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 493 | `POST /role/users/del` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |

### UserController（21条）

| 序号 | 当前method/path | 目标method/path | 响应/边界 | 源码消费关系 | 已有详设/结构证据 |
| ---: | --- | --- | --- | --- | --- |
| 494 | `POST /user/batch/import` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 495 | `GET /user/check` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 496 | `POST /user/create` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 497 | `DELETE /user/delete` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 498 | `GET /user/get` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 499 | `GET /user/get/name` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 500 | `GET /user/get/orgs` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 501 | `POST /user/get/roles` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 502 | `POST /user/group/change` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 503 | `POST /user/list` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 504 | `GET /user/my` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 505 | `POST /user/org/change` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 506 | `GET /user/password/change` | `POST /user/password/change` | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 507 | `GET /user/password/reset` | `POST /user/password/reset` | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 508 | `GET /user/point/change` | `POST /user/point/change` | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 509 | `GET /user/position/change` | `POST /user/position/change` | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 510 | `POST /user/role/change` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 511 | `POST /user/search` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 512 | `POST /user/sync` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 513 | `POST /user/update` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 514 | `POST /user/update/avatar` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |

## workflow

### WorkflowController（25条）

| 序号 | 当前method/path | 目标method/path | 响应/边界 | 源码消费关系 | 已有详设/结构证据 |
| ---: | --- | --- | --- | --- | --- |
| 515 | `POST /workflow/complete/{taskId}` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 516 | `GET /workflow/definition/activate` | `POST /workflow/definition/activate` | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 517 | `GET /workflow/definition/diagram` | 不变 | BINARY；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 518 | `GET /workflow/definition/find` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 519 | `POST /workflow/definition/list` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 520 | `GET /workflow/definition/suspend` | `POST /workflow/definition/suspend` | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 521 | `POST /workflow/delete/{processInstanceId}` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 522 | `POST /workflow/deploy` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 523 | `GET /workflow/deployment/delete` | `DELETE /workflow/deployment/delete` | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 524 | `GET /workflow/deployment/find` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 525 | `POST /workflow/deployment/list` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 526 | `GET /workflow/deployment/resource/find` | 不变 | BINARY；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 527 | `GET /workflow/deployment/resource/list` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 528 | `GET /workflow/find` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 529 | `GET /workflow/find/bybusinesskey` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 530 | `GET /workflow/instance/diagram` | 不变 | BINARY；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 531 | `GET /workflow/list/bybusinesskey` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 532 | `POST /workflow/list/history` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 533 | `POST /workflow/list/my` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 534 | `POST /workflow/list/process` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 535 | `POST /workflow/list/wait` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 536 | `POST /workflow/start/{processDefinitionKey}/{businessKey}` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 537 | `GET /workflow/task/assign` | `POST /workflow/task/assign` | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 538 | `GET /workflow/task/claim` | `POST /workflow/task/claim` | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 539 | `GET /workflow/task/delegate` | `POST /workflow/task/delegate` | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |

## wx

### WxMpController（26条）

| 序号 | 当前method/path | 目标method/path | 响应/边界 | 源码消费关系 | 已有详设/结构证据 |
| ---: | --- | --- | --- | --- | --- |
| 540 | `GET /wx/mp/checksignature` | 不变 | EXTERNAL_CALLBACK；第三方外部协议：不改wire，仅核外部边界 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 541 | `POST /wx/mp/checksignature` | 不变 | EXTERNAL_CALLBACK；第三方外部协议：不改wire，仅核外部边界 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 542 | `ANY /wx/mp/customservice/getkflist` | `GET /wx/mp/customservice/getkflist` | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 543 | `ANY /wx/mp/customservice/kfsession/getwaitcase` | `GET /wx/mp/customservice/kfsession/getwaitcase` | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 544 | `POST /wx/mp/info/create` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 545 | `GET /wx/mp/info/delete` | `DELETE /wx/mp/info/delete` | SELF_JSON；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 546 | `GET /wx/mp/info/get` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 547 | `POST /wx/mp/info/list` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 548 | `POST /wx/mp/info/update` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 549 | `ANY /wx/mp/jsapi/signature` | `POST /wx/mp/jsapi/signature` | SELF_JSON；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 550 | `ANY /wx/mp/material/add_material` | `POST /wx/mp/material/add_material` | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 551 | `ANY /wx/mp/material/add_news` | `POST /wx/mp/material/add_news` | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 552 | `ANY /wx/mp/material/batchget` | `POST /wx/mp/material/batchget` | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 553 | `ANY /wx/mp/menu/create` | `POST /wx/mp/menu/create` | SELF_JSON；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 554 | `ANY /wx/mp/menu/delete` | `DELETE /wx/mp/menu/delete` | SELF_JSON；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 555 | `ANY /wx/mp/menu/get` | `GET /wx/mp/menu/get` | SELF_JSON；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 556 | `ANY /wx/mp/message/custom/send` | `POST /wx/mp/message/custom/send` | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 557 | `ANY /wx/mp/message/template/send` | `POST /wx/mp/message/template/send` | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 558 | `ANY /wx/mp/oauth2/access_token` | `POST /wx/mp/oauth2/access_token` | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 559 | `ANY /wx/mp/oauth2/auth` | `POST /wx/mp/oauth2/auth` | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 560 | `ANY /wx/mp/oauth2/authorize_url` | `GET /wx/mp/oauth2/authorize_url` | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 561 | `ANY /wx/mp/oauth2/refresh_token` | `POST /wx/mp/oauth2/refresh_token` | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 562 | `ANY /wx/mp/oauth2/userinfo` | `POST /wx/mp/oauth2/userinfo` | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 563 | `ANY /wx/mp/user/get` | `GET /wx/mp/user/get` | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 564 | `ANY /wx/mp/user/info/batchget` | `POST /wx/mp/user/info/batchget` | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 565 | `ANY /wx/mp/user/sync` | `POST /wx/mp/user/sync` | SELF_JSON；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |

### WxPayController（31条）

| 序号 | 当前method/path | 目标method/path | 响应/边界 | 源码消费关系 | 已有详设/结构证据 |
| ---: | --- | --- | --- | --- | --- |
| 566 | `GET /wx/pay/closeOrder/{outTradeNo}` | `POST /wx/pay/closeOrder/{outTradeNo}` | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 567 | `GET /wx/pay/createOrder` | `POST /wx/pay/createOrder/product` | SELF_JSON；按自有/特殊响应契约核定 | 已有2条源码关系；未证明全部调用覆盖 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 568 | `POST /wx/pay/createOrder` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 569 | `GET /wx/pay/downloadBill` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 570 | `POST /wx/pay/entPay` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 571 | `GET /wx/pay/getSandboxSignKey` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 572 | `POST /wx/pay/info/create` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 573 | `GET /wx/pay/info/delete` | `DELETE /wx/pay/info/delete` | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 574 | `GET /wx/pay/info/get` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 575 | `POST /wx/pay/info/list` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 576 | `POST /wx/pay/info/update` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 577 | `POST /wx/pay/micropay` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 578 | `POST /wx/pay/parseOrderNotifyResult` | 不变 | EXTERNAL_CALLBACK；第三方外部协议：不改wire，仅核外部边界 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 579 | `POST /wx/pay/parseRefundNotifyResult` | 不变 | EXTERNAL_CALLBACK；第三方外部协议：不改wire，仅核外部边界 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 580 | `POST /wx/pay/parseScanPayNotifyResult` | 不变 | EXTERNAL_CALLBACK；第三方外部协议：不改wire，仅核外部边界 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 581 | `POST /wx/pay/queryComment` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 582 | `POST /wx/pay/queryCouponInfo` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 583 | `POST /wx/pay/queryCouponStock` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 584 | `GET /wx/pay/queryEntPay/{partnerTradeNo}` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 585 | `GET /wx/pay/queryOrder` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 586 | `GET /wx/pay/queryRedpack/{mchBillNo}` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 587 | `POST /wx/pay/refund` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 588 | `GET /wx/pay/refundQuery` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 589 | `POST /wx/pay/report` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 590 | `POST /wx/pay/reverseOrder` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 591 | `POST /wx/pay/scanPay/qrcode` | 不变 | BINARY；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 592 | `GET /wx/pay/scanPay/qrcodeLink` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 已有1条源码关系；未证明全部调用覆盖 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 593 | `POST /wx/pay/sendCoupon` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 594 | `POST /wx/pay/sendRedpack` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 595 | `GET /wx/pay/unifiedOrder` | `POST /wx/pay/unifiedOrder/product` | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |
| 596 | `POST /wx/pay/unifiedOrder` | 不变 | SELF_JSON；按自有/特殊响应契约核定 | 未建立人工源码关系；不等于无消费者 | 逐项审计未挂接字段详设引用（不等于文档完全不存在） |

## 直接Controller库存之外的候选

- 框架OAuth回调：`/login/oauth2/code/jia-client-oidc`。来源：`oauth/jia-oauth-client-starter/src/main/resources/application.yml`；配置声明已发现，当前部署启用未核定；保持外部协议，不新增Controller。
- 动态RUM接收端：当前无已核定活动路由，不虚构method/path；将来启用须登记真实接收方。
- 旧`docs/agent-client.mjs`：是WS可执行消费者，不是一个HTTP接口。退役设计已明确，不能加回旧协议。

完整operationId、源码绝对路径/行号/SHA、字段引用和逐项状态见同名CSV/JSON。所有596个producer源码SHA已在导出时核对。
