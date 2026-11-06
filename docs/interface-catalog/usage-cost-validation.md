
### 人民币usage成本账（2026-11-06候选；不代表接入或上线）

- 责任链：来源adapter验证实际调用与事项/身份绑定并确认usage包含语义；`TokenUsageCost`一次处理实际route/类别价和最终6位HALF_UP；`UsageCostServiceImpl`负责receipt幂等、修订版本及事项汇总短事务。既有文钱包/事项业务不重新承担成本验证。
- 重复计费风险：scope内source+sourceAttemptId固定attempt，receiptId+内容摘要固定一次证据。相同receipt重放返回原revision，即使已有较新修订；同ID不同内容拒绝当前成本写入，不发起Provider重试。不同真实尝试独立累计，不能靠相同文本或时间窗口合并。测试`UsageCostServiceMySqlTest`重复/旧回执/新尝试/并发。
- 归属污染风险：同attempt不允许换owner/task/source绑定；原生SQL边界包含tenant/client，成本汇总按owner/task精确查询，不依赖HTTP上下文。已验收/取消仍能接收原调用的迟到成本，不重新检查业务可执行状态，不写钱包或业务状态。测试scope/重新绑定/无业务表成本落账。
- 修订丢更新风险：费用汇总行→attempt锁顺序；新revision必须匹配expectedRevision，追加证据、当前attempt和汇总差额同一REQUIRES_NEW事务。失败只影响本次成本回执，可由durable来源重投，不将成本异常传播为业务失败；调用方须独立消费/捕获，不能仅凭此事务注解宣称主链已解耦。测试最终写失败回滚/并发/外层事务独立性。
- 缺数据不伪零：未核验usage为PENDING_USAGE，缺/不匹配价格为PENDING_PRICE，金额NULL；显式零有完整依据才PRICED。保留原始JSON、归一化依据、价格快照和修订历史，禁止查当前默认价覆盖旧调用。测试纯域及真实SQL pending→priced/撤回修订。
- DDL独立`db/economy-usage-cost.sql`，仅成本三表；人民币`DECIMAL(30,6)`，不新增CHECK/触发器，不改变文账DDL。显式schema-maintenance且`economy.usage-cost.schema-enabled=true`才创建，不随正常启动迁移旧资金。`EconomyUsageCostSchemaTest`验证精度、唯一键、外键和无业务CHECK。部署/生产迁移未执行。
