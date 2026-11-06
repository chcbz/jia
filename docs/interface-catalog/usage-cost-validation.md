
### 人民币usage成本账（2026-11-06候选；不代表接入或上线）

- 责任链：来源adapter验证实际调用与事项/身份绑定并确认usage包含语义；`TokenUsageCost`一次处理实际route/类别价和最终6位HALF_UP；`UsageCostServiceImpl`负责receipt幂等、修订版本及事项汇总短事务。既有文钱包/事项业务不重新承担成本验证。
- 重复计费风险：scope内source+sourceAttemptId固定attempt，receiptId+内容摘要固定一次证据。相同receipt重放返回原revision，即使已有较新修订；同ID不同内容拒绝当前成本写入，不发起Provider重试。不同真实尝试独立累计，不能靠相同文本或时间窗口合并。测试`UsageCostServiceMySqlTest`重复/旧回执/新尝试/并发。
- 归属污染风险：同attempt不允许换owner/task/source绑定；原生SQL边界包含tenant/client，成本汇总按owner/task精确查询，不依赖HTTP上下文。已验收/取消仍能接收原调用的迟到成本，不重新检查业务可执行状态，不写钱包或业务状态。测试scope/重新绑定/无业务表成本落账。
- 修订丢更新风险：费用汇总行→attempt锁顺序；新revision必须匹配expectedRevision，追加证据、当前attempt和汇总差额同一REQUIRES_NEW事务。失败只影响本次成本回执，可由durable来源重投，不将成本异常传播为业务失败；调用方须独立消费/捕获，不能仅凭此事务注解宣称主链已解耦。测试最终写失败回滚/并发/外层事务独立性。
- 缺数据不伪零：未核验usage为PENDING_USAGE，缺/不匹配价格为PENDING_PRICE，金额NULL；显式零有完整依据才PRICED。保留原始JSON、归一化依据、价格快照和修订历史，禁止查当前默认价覆盖旧调用。测试纯域及真实SQL pending→priced/撤回修订。
- DDL独立`db/economy-usage-cost.sql`，仅成本三表；人民币`DECIMAL(30,6)`，不新增CHECK/触发器，不改变文账DDL。显式schema-maintenance且`economy.usage-cost.schema-enabled=true`才创建，不随正常启动迁移旧资金。`EconomyUsageCostSchemaTest`验证精度、唯一键、外键和无业务CHECK。部署/生产迁移未执行。

#### 已核验来源与OpenAI直接Chat Completions适配（2026-11-06）

官方文档：`https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create`、`https://developers.openai.com/api/docs/guides/prompt-caching`。
直接响应的prompt总数包含cached子集，普通输入为两者差；completion总数已经包括reasoning及prediction子计数，不能再次加上。官方OpenAI缓存不另收写入费，不能制造cache-write用量。这不改写用户为其他渠道登记的价格。

`OpenAiTokenUsageNormalizer`仅处理该单次直接响应，不把Codex的thread累计通知当同一合同。缺原始计数/无法确定缓存子集、计数矛盾或涉及音频费率时待核对；无害新增字段允许。实际channel/model/tier由来源绑定，不用请求模型替代响应模型。对应5项集中测试，不让费用服务再重复Provider语义。

本地只读核对Maven官方发布的Spring AI `spring-ai-model`/`spring-ai-openai` 2.0.0 source jar（与项目plugin指定版本一致）：`OpenAiChatModel`将Provider的CompletionUsage保留为nativeUsage，保留真实响应id/model，缓存读取映射自prompt细目。文档与制品摘要证据位于根任务`usage-provider-source-20261106.json`。只读获取源码不运行模型、不修改生产依赖。

官方app-server文档`https://learn.chatgpt.com/docs/app-server`只确认tokenUsage通知；未证明last为整个turn或total可直接归属一次收费。因此Runtime原始证据继续UNVERIFIED，不因直接Chat Completions适配已完成就套用到Codex。

### Recommendation source adapter (candidate 2026-11-06)

- Trusted task context is passed by `AssigneeRecommendationService`; the model never supplies owner/task identity. `RecommendationCostRecorder` writes one independent pending attempt before the paid call and its response evidence before business-JSON validation. Failed inference, crash or response-write failure remains an explicit pending attempt, not free usage and not permission to replay the provider. No wallet/root lock is held across the call; there is no volatile async queue.
- `ModelPriceCatalog` reuses the configured exact-key index independently of workload estimation. The pre-call manual CNY price is retained. Actual model/channel/tier mismatch or incomplete evidence stays pending; configuration updates cannot silently reprice the original invocation.
- SDK automatic retries are already disabled by the existing `AiModelBeanPostProcessor` when configuring the provider client (Spring AI ignores request-level maxRetries for this transport) so one observed call is not misrepresented as multiple hidden billable attempts. A deliberate new recommendation has a distinct source attempt. This does not assert that an unknown network failure incurred zero upstream cost.
- Spring AI 2.0.0 drops the SDK's standard `serviceTier` property from ChatResponseMetadata. The adapter retains raw usage and prices only when actual tier evidence exists; missing tier is not replaced with requested tier. Direct provider-tier capture remains to be completed, so this candidate is **not** full priced-source coverage. Likewise, no cross-process response journal/reconciliation is claimed for the narrow response-before-database-failure window.
- Regression: `RecommendationCostRecorderTest`, `AssigneeRecommendationModelTest` (real HTTP 500 verifies no hidden SDK retries), and `AssigneeRecommendationServiceTest` trusted binding. Formal evidence belongs to the subsequent fixed-source Flow run, not the earlier ledger Run560.

### Native recommendation transport candidate (2026-11-06; formal verification pending)

Recommendation alone now consumes the SDK `ChatCompletion` directly using a lazy singleton transport built from the existing connection options/customizers. This avoids the Spring response metadata loss without patching provider JSON or moving provider logic into the business layer. Actual `service_tier=default` maps to the catalog's `standard`; missing identity/tier/usage stays pending. Native raw usage, including provider extensions, is retained before parsing the business result. Request retries are disabled on the constructed client, not only request options. HTTP fixture tests cover actual/requested model+tier differences, structured response settings, no tools, single request on 500, and Spring lazy lifecycle; usage tests distinguish explicit zero from missing evidence. This candidate supersedes the previous transport limitation only after its fixed-source Flow tests pass. The response-before-DB-failure reconciliation gap remains open.

### Native historical evidence candidate (2026-11-06; not yet formally verified)

`NativeExecutionUsageCostRecorder` owns source binding; `findForNativeUsageCost` is the historical SQL bypass with exact tenant/client/execution/task/run/target and original grant executor owner/binding matched to the current authenticated Runtime. It intentionally ignores grant state/version and execution business state; the active execution query is unchanged. Risk: using active grant versions loses late cancellation costs; trusting request owner/rebound agents crosses authorization. Failure scope: only evidence delivery (404), never task/wallet mutation. Tests: `TaskExecutionCancellationMySqlTest.lateNativeCostAuthorityUsesOriginalExecutorBindingNotCurrentGrantVersion`, recorder tests and native route/controller tests.

The native endpoint records execution evidence as `PENDING_USAGE` with no guessed model, normalized counters or post-call price. Stable terminal receipt identity plus cost-domain content/revision checks owns replay protection (409 on conflicting contents). Runtime retains evidence and a separate local acceptance acknowledgement across business completion/restart. This does not establish per-provider-attempt pricing or before-call discovery. Cross-repo wire contract and unresolved scope: root spec §6.3, interface standard main v1.4 SHA256 2d36ffac1dc52c6db8d54b860695edd845b6688ea0808528c53218e39aec7a08.
