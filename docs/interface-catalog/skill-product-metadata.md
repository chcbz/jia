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
