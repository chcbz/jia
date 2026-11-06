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

## 2026-11-05 候选：历史成果交付索引

核对源码基线 `80d74439a729c80c4922da75891ebd00992a7b02`，本节描述后续维护候选，**未编译/未正式测试/未接入主机安装器/未执行生产迁移**。

- 原DDL候选 `chat/jia-chat-mapper/src/main/resources/db/chat-outcome-delivery-index-20261105.sql` 只加两列和索引；加列不等于旧数据准备完成。
- 复用 `ChatTypedDeliberationSchemaInitializer.backfillDeliveryIndex(configuredTenant)`，它是单独调用的方法，**不由 `initialize()` / `run()` 调用**。只在现有隔离维护上下文显式注册该维护器、开启原有enabled/allowMigration配置后使用；不能启用生产应用的schema-maintenance profile跑全模块迁移。无需新增服务、定时任务、表或常驻框架。
- 主机Owner固定授权范围、数据库/租户配置和真实备份，停用旧writer，按现有受控迁移流程加列/索引后调用。方法复用同一 `cyf:chat-deliberation:v2` schema锁，并先核验已有完整catalog；schema锁不阻止业务writer，不能代替停旧writer。当前安装器尚未调用这个方法，不因源码存在而宣称部署自动完成了回填。
- 扫描固定租户、起始MAX(outcome_id)上界，主键游标每批100条，不OFFSET、不一次载入全表。每批独立事务，读取FOR UPDATE后只更新空的两列；正常服务启动和请求路径不扫描旧数据。100是维护批大小，不是业务请求/历史长度门槛。
- 源数据是此前已提交的 `binding_json` 和 `outcome_json`，规则与 `ChatActionFinalService.persist` 一致：CHAT的可交付ANSWER、ACTION_REQUEST和SUBMISSION_REQUEST记录目标；parent仅取deliveryRelation，不取admission parent。ACTION候选依然不是执行完成证明，查询仍使用真实执行事实。
- 历史typed v1及inspection v2没有交付声明，保留空索引且不自动转换/重放；v5普通ANSWER/澄清/修改建议/INSPECT不产生head。允许无害附加字段；无法识别的版本/语义、缺失必要字段、已有非空索引冲突使当前批回滚，返回具体outcome ID供人工定位，不修改原文来强行适配。此前已提交批次保留，下次从头重跑会核对并跳过一致项。
- 回填仅修改两个派生列。JSON正文、final_digest、text、创建时间、admission/turn/execution/review均不写。失败不把维护状态标为READY，也不启动或恢复业务执行。
- 返回scanned/updated/batches只是本次维护统计。正式启用读取前还须保留同源码正式测试、MySQL隔离演练、实际回填结果及长历史查询计划，不能把返回数字当线上业务验收。恢复遵循原受控备份流程；不要用旧整库快照覆盖迁移后新增事实。

回归入口：`ChatOutcomeDeliveryIndexBackfillTest`（真实JDBC小批量/原文不变/租户大小写/重跑/冲突与写入异常回滚/固定上界/历史版本），`ChatOutcomeDeliveryIndexTest`（当前writer）。Java回归尚未运行，不以测试源码替代证据。

### 2026-11-06 候选：使用同 Run JAR 的一次性回填入口

`ChatTypedDeliberationSchemaInitializer.main`只接受`--backfill-delivery-index`，不创建Spring容器、不注册worker、不调用initialize/其他模块迁移。主机Owner核对固定制品摘要、真实备份、发布/schema锁、旧writer已停止及两列/索引已按授权准备后，向子进程传入现行部署配置的标准环境变量`SPRING_DATASOURCE_URL/USERNAME/PASSWORD`及`CYF_SECURITY_FIXED_TENANT_TENANT_ID`（密码不放参数或日志）。

```sh
java -Dloader.main=cn.jia.chat.config.ChatTypedDeliberationSchemaInitializer \
  -cp /absolute/verified-run/cyf-api-kit.jar \
  org.springframework.boot.loader.launch.PropertiesLauncher --backfill-delivery-index
```

入口复用原backfill方法、完整catalog核对和共享schema锁，不重新实现SQL/JSON抽取或启动全应用。主机直接执行已通过Flow的字节码；不在主机编译或伪造Flow身份。输出仅scanned/updated/batches，执行失败保持非零退出；不得把前置授权/备份/停writer当成这个命令已自动完成。当前仅源代码候选，尚未Flow、主机演练或生产执行。

本候选还删除了事件event_version冗余UPDATE，物理列保留为0；回退到仍读取物理event_version的旧JAR前，需要在停止新writer、固定真实备份与原维护锁的受控窗口，按现行配置租户将`chat_conversation_event.event_version=0`的行回填为其`event_sequence`。这是恢复派生列，不改payload/消息/完成事实，不删除新事件。不能仅替换旧JAR然后声称回退兼容，正常启动不再自动补全这一列。
