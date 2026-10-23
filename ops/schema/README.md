# Schema 与正常启动分离（2026-10-23 候选）

覆盖：本提交的42个SchemaInitializer、其@Bean工厂、archive启动器、typed deliberation就绪接口和Camunda配置。源码基线及正式验证见根任务handoff `ARCHIVE-CONTENT-DECOUPLING-20261023.md`；本说明不代表已经上线。

## 正常应用

- 不启用 `schema-maintenance` profile。功能开关只控制功能，不再隐式安装/迁移/全量对比数据库结构；历史 `allow-additive-migration` / `schema-enabled` / `install-on-startup` 开关单独打开也不会注册维护器。
- 42个维护器及ArchiveBootstrap/ChatSchemaReadiness不进入普通容器。@Component与@Bean两条注册路径均隔离，保留维护模式内部的依赖顺序及锁。
- ArchiveQuestionBootstrap只启动已启用的worker，不初始化结构。数据库必须先部署准备好；worker原有错误处理、身份隔离、事务不变。
- typed deliberation单独读取既有 `chat_deliberation_schema_version` 的version=3、stage=APPLIED（最多2行）。关闭功能不查库；无记录为UNAVAILABLE；非APPLIED、重复或查询失败均失败关闭。不会伪造READY，也不在请求或启动中回退迁移。
- 该标记仅证明既有版本状态，**不替代全量DDL/约束验收**。版本3没有覆盖后来所有增量变更；完整catalog检查必须在部署显式维护时完成，不将这个旧版本标记宣称为完整部署证明。
- Camunda禁止自动建表/升级，保留引擎所需的版本兼容性检查。连接与业务SQL的真实错误不掩盖。

## 显式部署维护

原SQL、完整catalog校验、迁移白名单、数据库锁与初始化方法均保留，不因本次修改放宽约束。沿用已批准的专属迁移/部署工具，不把全模块迁移搬进正常服务启动。

`schema-maintenance` 是维护器注册边界，不是一个安全的“全部升级”命令。只在隔离、无业务worker的维护/测试上下文中，显式注册本次授权的模块、DataSource和前置依赖；保留原模块功能开关。禁止给生产服务常驻配置此profile，也不要直接以整个JiaApplication启用它做批量迁移（原迁移器可能执行DDL/回填）。已有直接调用 `.initialize()` / `.afterPropertiesSet()` 的专属工具不变。

部署顺序：固定源码与制品 → 核验授权/备份/锁 → 显式运行所需迁移与完整校验 → 安装同Run制品 → 正常启动（无维护profile）→ 健康/业务验收。此提交没有执行新的生产DDL，也没有证明现有安装器已经包含所有模块的完整校验；部署覆盖缺口必须单独核实，不靠启动兜底。

新增模块必须保持这条边界。回归包括组件扫描/Bean工厂默认排除、显式维护保留、版本探针的成功/缺失/失败，以及既有迁移/约束回归。正式测试走Flow；60秒启动和Metaspace增长仍须独立实测。
