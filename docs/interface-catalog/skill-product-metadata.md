# 技能发现文案维护（2026-11-03）

`PUT /agent/skill-maintenance/product-metadata`，JSON请求字段：`productId`、`name`、`description`、`expectedProductVersion`（十进制字符串）、`positionAuthority`（沿技能发布的appointmentId/appointmentRevision/positionRevision/agentId/bindingId）。返回既有JsonResult，data为productId/name/description/productVersion/disposition（UPDATED或RECORDED）。只持有ROLE_ADMIN不授权。

复用技能管理岗位publish权限和事务，锁定平台商品；expectedProductVersion防止覆盖并发更新，相同内容且版本匹配或恰好增加一次支持丢回执重试。只更新name/description/version/update_time；不改变skillKey、版本包、价格、权益、权限、安装或任务准入。错误继承现有publication handler：403岗位不足、404不存在、409商品/版本改变、400字段格式错误。

名称非空且≤255、描述非空且≤4000，沿既有产品字段容量并避免模型发现上下文缺文案；本接口仅接受上述字段，禁止借文案请求修改包/价格。文本是用途说明，不是授权，也不保证未安装的新包能力可用。推荐读取现有商品文案；固定包SKILL.md独立，不因本接口修改。

回归：SkillPackagePublicationControllerTest、SkillPackagePublicationServiceTest、EconomySkillMarketplaceMapperH2Test；推荐消费者维持已有契约。上线/业务更新证据独立于此文档。

## 2026-11-03 商品退出发现目录

`PUT /agent/skill-maintenance/product-retirement`，沿用本页认证、岗位授权和JsonResult信封。
请求仅包含 `productId`、十进制字符串 `expectedProductVersion`、与发布一致的 `positionAuthority`。
响应data：`productId/status/productVersion/disposition`，status=`RETIRED`，首次disposition=`RETIRED`，同一CAS重试=`RECORDED`。

- 岗位publish权限由既有PositionAuthority在事务内锁定并复核；平台SYSTEM/SKILL_STORE产品才可操作，不依赖ROLE_ADMIN。
- PUBLISHED→既有RETIRED状态，仅增加商品version并更新time。缺产品404，权限403，版本/非平台/状态冲突409；不删除固定版本或文件，不改安装、订单、价格、授权和历史成果。并发发布/报价创建复用商品锁与现有published检查，无业务特化技能名分支。
- RETIRED不在新市场、安装候选、承办推荐经验中。推荐不得回退skillKey重新推荐已退出商品；旧安装仍可在管理界面查看原标题、停用/卸载，保留已授权执行事实及固定包读取。未安装文档技能的Agent不会自动获得文档能力。
- 不提供隐式恢复发布，不因服务重启的seed而重上架；seed保留退休指针。没有跨技能安装迁移或别名伪装。
- 本次text-file/pdf-extract的功能入口由已有document-workbench承接（文本、表格、文本型PDF；非扫描OCR），但旧安装不自动重装。技能包与本机执行职责不变，不修改Web/Runtime协议。

回归：SkillPackagePublicationControllerTest/ServiceTest验证岗位、CAS、重试与固定版本不变；EconomySkillMarketplaceMapperH2Test真实SQL验证目录与购买准入关闭/已安装引用保留；SkillManagementTransactionTest真实MySQL验证安装后退出、推荐去重、管理可用且业务事实快照不变；EconomyPlatformSkillSeederTest验证启动不重上架。

## 岗位技能分类（2026-11-08）

技能类型是共享商品事实：`agent_skill_product.skill_type`取`GENERAL/POSITION`，在同一tenant/client下不随查看Owner变化，升级版本沿用商品分类。岗位管理保留Owner隔离的技能版本绑定、岗位、任命和资源权限，不以其JSON推导普通安装资格。

既有`POST /agent/positions/v1/skills`绑定已审核商品；从GENERAL提升为POSITION须同时具有`skills:platform`的SKILL_REVIEW/SKILL_PUBLISH权限，商品类型与Owner绑定在同一事务提交。其他Owner可绑定已有相同分类，不能降级POSITION。响应字段及`POSITION_MANAGED_SKILL`不变。安装/报价/购买/变更/重试由共享商品事实限制，管理员不豁免；原幂等回执仍只读返回，任职专用安装路径不经普通准入。

写入锁序保留Owner岗位根→商品行；共享分类变更与普通准入使用同一商品锁，读取目录一次查询共享岗位技能key集合，不扫描其他Owner状态。包安装不授任职和业务资源权限。

已有库必须在获授权的停写/备份/发布锁窗口执行`db/migrations/20261108-shared-skill-type.sql`中的显式routine；参数为实际tenant/client，先拒绝历史分类冲突及无效POSITION绑定，再新增列并回填。仅回填商品，不复制/改写Owner任职、权限、历史回执。DDL不事务回滚；失败保持停写、保留实际阶段并评估固定备份恢复，不在启动时补列或迁移。源码/隔离回归完成不代表生产迁移或部署。
