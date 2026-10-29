# 积分账户与用户身份解耦（2026-10-29）

用户决定：积分在point模块另建表，不进入economy，不再由全局user_info持有余额。

## 合同

- `point_account`唯一键为`(tenant_id, jiacn)`。旧余额按用户全局共享于不同客户端，故不能把client加入账户键、给同一用户按应用复制余额。point_record中的client仍可保留来源/授权含义。
- tenant由后端固定实例配置选择，不从OAuth客户端数据库字段、请求参数或用户资料读取；后台和HTTP走同一分区。暂复用现有fixed-tenant/legacy-single-tenant配置，不新增第二套可冲突的租户配置。
- tenant0余额按旧user_info.point一次性精确迁入，包括0余额用户；NULL按既有COALESCE语义为0。tenant1不能继承生产积分，新账户从0开始。
- 余额为既有int合同的非负值。精确用户标识、上溢/负数、更新行数保护对应资金/积分完整性；账号存在与业务权限由入口校验。
- 变更必须加入调用方现有事务，账户锁保持至该事务结束；与point_record、签到、兑换/退款记录同提交或回滚。新方法不自行重试，不绕过原有业务幂等。
- 读取不创建账户，不读回旧user_info，不存在账户读0；表不存在报错，不把部署缺表伪装成0。

## 当前阶段

基础提交433d190e之后已合并2026-10-29查询的远端develop `648d644bc2fcc27c3ea7d67d49d6f541370c55eb`（合并提交732c70547d），在同一隔离工作区继续接入实际调用方。PointService、GiftService、UserService旧积分适配器、用户Controller及微信余额读取已改为point账户；用户point为非持久化投影，列表批量读、列表/搜索积分条件按固定业务tenant查point_account。

**仍是未完成候选，不可直接部署。** Java回归/完整身份权限链尚未验证，OAuth/用户分区列去除尚未完成。未建生产表、未迁移余额、未删除任何生产列；新服务无启动DDL，不做自动迁移。完整候选再集中走Flow，避免为半成品重复计费。

## 受控迁移与恢复

1. 固定源码、必要回归、制品与生产目标，保存原schema及数据备份和摘要。
2. 停止所有旧积分写入口（API/微信回调/后台等）；导入器命名锁仅互斥导入器，不替代停写及发布锁。
3. 显式安装并核对point-account.sql及point-business-scope-indexes.sql（先核对实际索引，不盲重跑DDL）；在选定数据库加载point-account-import-legacy.sql，单独调用过程。
4. 导入仅INSERT tenant0；任何已有tenant0账户即拒绝，不用ON DUPLICATE覆盖现值。核对每个账户余额及行数，保留来源/结果证据。
5. 切换已完成的所有读写调用方，核对事务、余额和流水后才恢复写入；不在源仍有旧写入时做在线快照复制。
6. 只有目标三列及point不再有运行代码/工具依赖，才进入下一阶段删列。当前SQL不执行任何DROP COLUMN。
7. 不确定CALL是否已提交时查询目标及记录，不盲重放。目标有新写入后禁止重灌旧余额，回退必须按同一停写边界迁回最新余额，不能直接启动旧JAR读陈旧user_info.point。

## 已接入与未完成边界

- PointService签到/推荐/概率/初始化/奖励使用账户返回余额写流水；插入失败抛出异常回滚。签到/推荐先锁参与账户，再按同tenant当前读检查资格；多账户按稳定顺序加锁。
- GiftService按固定tenant读取/修改礼品和订单，兑换锁库存，退款锁订单，余额/退款流水/状态同事务。数量须正、成本不得负、整数乘法不得溢出；现金订单不能从请求携带可退积分。保留现行支付状态和不自动回库存语义。
- UserService旧积分适配器保留对外方法，使用精确唯一身份查询后调用point账户，不再写user_info余额。通用create/update/upsert忽略point写输入；余额投影不进入登录身份查询。
- UserController的get/list/search及生产my、微信余额展示改读新账户；列表批量查询不按每用户增加SQL。保留搜索OR与列表AND原语义，point=0包含本环境尚无账户的用户，不从tenant0兜底。
- 现有微信答题回调测试替身改用point_account；第三方协议、回放/幂等合同不变。该测试的积分协作者仍是替身，不冒充真实PointService回归。
- **尚未完成**：OAuth/用户scope列解耦的正式回归及权限/成员链全量核对；签到位置/推荐人仍写共享用户资料，灰度路由保持最小白名单。礼品调用者ACL和外部支付凭据还需原完整审计，不凭tenant谓词宣称全功能灰度就绪。
- 运维/初始化SQL及历史备份中的旧user_info.point仍需按切换生命周期分类，不能机械全库替换。回退最新余额迁回方案/真实备份恢复实测、Flow测试构建及安装未完成。

## 必要校验的具体依据

| 风险与条件 | 失败范围 | 实现/回归入口 |
|---|---|---|
| 并发变更后以旧用户余额生成流水 | 本次积分事务回滚，不能返回成功 | PointServiceImpl / PointServiceImplTest、PointAccountTransactionTest |
| 重复取消/跨tenant订单被退积分 | 非本环境订单视为不存在，已取消拒绝；写入失败回滚退款及流水 | GiftServiceImpl / GiftServiceImplTest、PointAccountTransactionTest |
| 负数量/负成本或乘法溢出把扣分变加分 | 拒绝本次兑换/概率变更，不影响其他账户 | GiftServiceImpl、PointServiceImpl及对应测试 |
| 缺失/歧义用户或请求覆盖业务tenant | 变更前拒绝；tenant只读配置 | 精确用户查询、PointAccountServiceImpl及定向测试 |
| 删旧point列后ORM仍引用，或悄悄丢弃过滤 | 不回退旧列；有point过滤但无业务scope拒绝查询 | UserPointProjectionMapperTest、12项隔离SQL检查 |

正式Java回归在Flow执行；本机SQL验证只能作为轻量隔离证据，不能冒充完整应用或生产验收。

## 本轮验证

2026-10-29本机显式MySQL socket独立随机测试库、合成数据：**12/12 SQL测试通过，103.985秒**。原9项账户/导入检查之外，执行实际batch mapper SQL、用户XML的积分过滤及排序投影，覆盖tenant1不取tenant0余额、缺账户为0及user_info删除旧point后过滤仍工作。日志位于根handoff证据目录；不是Java/Spring/MyBatis完整运行回归。

新增/更新Java测试包括point账户与业务单测、真实account Mapper+Spring事务回滚测试（订单/流水协作者使用同事务JDBC）、用户实际ORM/XML兼容测试、用户/微信兼容fixture。**均尚未执行，正式验证待Flow。** XML/Python语法与diff空白检查通过。

生产只读聚合检查：jia的point_sign 1313行、point_referral 2行、point_gift 7行、point_gift_usage 9行，全部tenant0，无NULL/非0；本次严格tenant谓词不会仅因这些历史记录的scope值而隐藏它们。这不代表全部生产调用已验证。元数据只有主键，新增资格查询索引随受控迁移执行，尚未创建。


## 身份与业务成员解耦候选（2026-10-29，尚未发布）

- `user_info`不持久化tenant/client/point；`oauth_client`不持久化tenant，但保留OAuth协议自身client_id。全球身份查询不以业务分区兜底或筛选；资料写入忽略请求携带的归属/余额。
- `user_business_membership(tenant_id,client_id,user_id)`单独保存业务归属，不代替角色/权限。旧NULL client保存为`''`仅表示环境成员；按客户端筛选不匹配此行。实际库存中7个有client的用户均无等价角色/组/组织归属，不能仅用这些关系替代旧筛选。
- 新建/upsert在身份事务内，以进程tenant和已准入调用者client写成员，幂等不覆盖旧归属。`sync`外层事务覆盖self-call；无事务直接调用成员Service拒绝执行。既有默认角色和关系读写未宣称完整灰度隔离，原路由限制继续保留。
- OAuth签发使用全局精确客户端注册记录+配置`cyf.security.oauth.issuer-client-ids`；环境tenant由进程确定。签名tenant仍作为环境绑定供既有API/Runtime校验，不代表身份表或OAuth客户端拥有租户。
- 生产allowlist缺失会启动失败；必须先将原准入集合写部署配置。灰度仅准入固定client，不能靠删列开放生产签发灰度client。测试profile只配置合成`jia_client`，不替代生产清单。

### 切换条件与验证边界

1. 停止旧身份/积分写入并备份；准备账户及成员DDL，运行显式导入并核对全量计数/余额/精确归属。导入程序不会自动CALL或删列，命名锁只锁导入者，不冒充应用停写锁。
2. 配置生产issuer清单、安装受测不可变制品后验证旧业务。删列必须最后进行；不能先删列再让旧应用写。
3. 新版本写入后，不能恢复旧余额/成员快照覆盖新事实；完整受控恢复实测仍待完成。
4. 新增5项隔离MySQL检查已通过（17.658秒）：精确迁移/源数据不变/不赋权、拒绝覆盖、坏数据整批回滚、删列筛选、实际mapper幂等及事务回滚。SQL筛选测试独立构造等价谓词，不冒充Java DAO执行；Java实际ORM tests待Flow。
5. 新增/扩展`UserPointProjectionMapperTest`、`OauthClientIdentityMapperTest`和`UserBusinessMembershipTest`，覆盖删列CRUD/查询及配置/事务声明。Java、validateLayering、应用启动及生产行为尚未验证；无生产迁移或发布。
