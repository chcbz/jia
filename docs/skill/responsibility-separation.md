# 技能 / economy 源码职责分离 v1

2026-11-04，基于 develop `234d98febc3cc66ceade7f4709cee43d3172c9c1`。本文件描述候选源码；不证明已经测试或上线。

- agent-core/mapper/service 拥有目录、包版本、安装、权益、管理意图、授权、回执、托管凭据及技能种子。economy-core 保留报价、订单、交易回执三个实体。
- agent 技能服务只通过 economy-api `SkillTradeService` 访问交易事实与资金操作。交易服务实现封装报价/订单/交易回执 Mapper、账户初始化和已有 PostingService。订单 CAS、冻结/捕获/退款及资金幂等保留原实现。安装事实不能由支付成功代替。
- 托管实例凭据查询通过 `SkillHostingCredentialLookup`；技能服务不直接访问 hosting Mapper。原有唯一 key / 绑定 / 租户及身份判断保持。
- 免费及岗位安装不依赖经济预览开关；非零资金操作由 economy 内部开关保护。技能传输仍保留既有 tenant 0 支持范围，不借本次改动开放灰度租户。
- 跨域操作使用原 TransactionTemplate / REQUIRED，保持原来的锁顺序和提交后派发，不引入新事务管理器或异步资金调用。
- `validateLayering` 检查 economy 不反向引用 agent、技能代码不访问 economy Entity/Mapper、非交易实体不留在 economy。

## 物理兼容与维护入口

21 张历史 `economy_skill_*` 物理表名不变，18 张生命周期表归 agent，3 张交易表归 economy；没有复制、双写或生产 DDL。

七表历史建表资源 `db/economy-v0-skill-marketplace.sql` 是已有联合安装合同：同时创建目录/安装和它们引用的三张交易表。首版整体迁入 agent-mapper，由唯一 `SkillSchemaInitializer` 在显式 `schema-maintenance` 下协调；不在 economy 注册第二个 initializer，不改变 DDL 字节和顺序。该联合维护入口不授予技能业务服务直接写交易表的职责。其余技能 DDL、种子及包资源同步迁移，六个内嵌技能包字节不变。

保留现有 `economy.skill.*` 配置键和资源名，避免因源码职责分离改变运维输入；它们不代表领域所有权。Web/Runtime wire 契约不变。

## 回归入口

定向覆盖 agent/economy core 状态机、拆分 Mapper 的作用域/CAS、schema 与种子、真实安装/支付/退款事务、免费且经济关闭场景、岗位无订单及禁止手动安装、凭据与已安装列表。正式执行记录在根 handoff `docs/implementation/handoffs/REUSABLE-SKILLS-20261102.md`，CI 证据绑定候选 commit/tree，不复用迁移前的成功结果。
