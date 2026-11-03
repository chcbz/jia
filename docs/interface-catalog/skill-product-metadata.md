# 技能发现文案维护（2026-11-03）

`PUT /agent/skill-maintenance/product-metadata`，JSON请求字段：`productId`、`name`、`description`、`expectedProductVersion`（十进制字符串）、`positionAuthority`（沿技能发布的appointmentId/appointmentRevision/positionRevision/agentId/bindingId）。返回既有JsonResult，data为productId/name/description/productVersion/disposition（UPDATED或RECORDED）。只持有ROLE_ADMIN不授权。

复用技能管理岗位publish权限和事务，锁定平台商品；expectedProductVersion防止覆盖并发更新，相同内容且版本匹配或恰好增加一次支持丢回执重试。只更新name/description/version/update_time；不改变skillKey、版本包、价格、权益、权限、安装或任务准入。错误继承现有publication handler：403岗位不足、404不存在、409商品/版本改变、400字段格式错误。

名称非空且≤255、描述非空且≤4000，沿既有产品字段容量并避免模型发现上下文缺文案；本接口仅接受上述字段，禁止借文案请求修改包/价格。文本是用途说明，不是授权，也不保证未安装的新包能力可用。推荐读取现有商品文案；固定包SKILL.md独立，不因本接口修改。

回归：SkillPackagePublicationControllerTest、SkillPackagePublicationServiceTest、EconomySkillMarketplaceMapperH2Test；推荐消费者维持已有契约。上线/业务更新证据独立于此文档。
