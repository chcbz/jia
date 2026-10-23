# 存量接口字段级续查：Dict / Dwz / Msg

核对日期2026-10-14；公共规范1.2；API `7e2d07a05b9e9b18461e97abcff44642504320ff`，Web `a900ea4cff8158decc9b28888392c091fd0c62fe`。仅源码/目标设计，**23条有字段设计，不等于23条全部闭合或已实现**。身份与缓存语义缺口见§5，不以Entity类型名代替字段定义。Owner本聊天主Agent；不改业务代码、不调用线上接口。

## 1. 类型与投影（目标）

沿用critical-contracts的Q/R/D0/D1/T。响应以下列字段集合为准，所有字段必出现；`?`可null。请求字段是否可省略另列，固定对象禁止未知字段。数字ID到Java Long须校验范围；时间仍是毫秒安全整数，不能因Java Long统一改字符串。

| 目标DTO | 字段全表 | 现行Entity→投影差异 |
| --- | --- | --- |
| DictView | dictId:D1, type:String?, language:String?, name:String?, value:String?, url:String?, parentId:String?, dictOrder:int32?, description:String?, status:ENABLED或DISABLED?, createdAt:T?, updatedAt:T? | id→dictId；status 1→ENABLED/2→DISABLED；createTime/updateTime→createdAt/updatedAt。parentId源码为String，不强改数字。 |
| ShortLinkView | shortLinkId:D1, ownerJiacn:String?, orig:String?, uri:String?, expiresAt:T?, status:ENABLED或DISABLED?, pv:整数0..2147483647?, createdAt:T?, updatedAt:T? | id→shortLinkId，jiacn→ownerJiacn，expireTime→expiresAt；status 1→ENABLED/0→DISABLED。pv源码Integer，不假装已经是Long。 |
| MessageView | messageId:D1, title:String?, content:String?, url:String?, type:String?, userId:D1?, status:DELETED或UNREAD或READ?, createdAt:T?, updatedAt:T? | id→messageId；status 0→DELETED/1→UNREAD/2→READ；createTime/updateTime改名。type不是已核定枚举，保持字符串。 |
| PageView<V> | items:V[], pageNum:整数1..2147483647, pageSize:整数1..100, total:D0 | 原JsonResultPage根级pageNum/total及data列表合入R.data；补真实生效pageSize。 |

三个View不投影tenantId/clientId，不把它们再作为业务请求字段。响应可null源于现行可空字段，不能悄悄以空串/0替换。遇到数据库未知状态值不是允许任意数字穿过wire，必须作为数据不变量错误返回500/E500并记安全诊断；是否存在这类数据本轮未查库。

### 1.1 请求DTO完整字段

- **DictCreate**：type/language/name/value/url/parentId/description均为可选String?；dictOrder可选int32?；status可选ENABLED或DISABLED。缺省status由服务明确设ENABLED，不接受客户端id/租户/时间。沿用现有可空业务字段，不借统一协议增设“名称必填”等需求。
- **DictUpdate**：dictId:D1必填；其余同Create但全部可选；提供null与省略不混用：本轮不引入字段清空能力，更新项不得null，省略保留原值。字典不因无更新字段写时间。原DAO是否忽略null不作为wire语义依据。
- **ShortLinkCreate**：orig:String必填；expiresAt:T可选，缺省仍按服务日历加一年。ownerJiacn来自可信用户，不接收原jiacn字段；原只读取这三个值，不允许全Entity反序列化。orig允许的URL协议、长度与公开跳转安全策略待§5核定，未伪报已闭合。
- **ShortLinkUpdate**：shortLinkId:D1必填；orig:String、expiresAt:T、status:ENABLED或DISABLED可选且非null；uri、pv、归属、时间戳不能通过管理输入直接改写。输出为事务后读取的ShortLinkView，不回显未经合并的请求Entity。
- **MessageCreate**：userId:D1必填（收件人，不是调用方身份）；title/content/url/type可选String?；状态由服务设UNREAD。发送权限与收件人同租户/客户端可达性校验，不能允许自行指定DELETED或绕过认证。
- **PageQuery<S>**：pageNum可选整数1..2147483647默认1；pageSize可选Integer整数，缺省/null默认100；按既有setter将整数夹取到1..100，响应pageSize返回实际生效值。字符串、小数、布尔及超Integer范围拒绝400/E003，不做类型强转；search可选S，缺省或null归一成空筛选；orderBy可选下列枚举字符串。2026-10-15纠正：保留现有分页归一化，不因接口统一额外引入数值越界400策略。三个Controller均未使用draw/extra_search，目标不接收这两个无效输入（其他使用extraSearch的接口另查）。
- **DictSearch**：dictId:D1、type/language/name/value/url/parentId/description:String、dictOrder:int32、status:ENABLED或DISABLED，均可选非null；按原实体匹配语义精确匹配，不引入模糊搜索。
- **ShortLinkSearch**：shortLinkId:D1、orig/uri:String、expiresAt:T、status:ENABLED或DISABLED、pv:整数0..2147483647，均可选非null；归属由可信用户限定。
- **MessageSearch**：messageId:D1、title/content/url/type:String、userId:D1、status:DELETED或UNREAD或READ，均可选非null；userId只能作为与可信uid相等的声明，缺省仍绑定可信uid，不代表查所有人。
- **排序白名单**：Dict为`dictId asc/desc`、`dictOrder asc/desc`；ShortLink为`shortLinkId asc/desc`、`createdAt asc/desc`；Message为`messageId asc/desc`、`createdAt asc/desc`。分别映射固定SQL列id/dict_order/create_time，方向仅asc/desc；省略默认各ID desc。不能拼接传入SQL。现Web消息`id desc`同批改为`messageId desc`。

上述长度不能从测试DDL直接认定线上数据库约束：Dict测试列type100/language50/name255/value255/url100/parent_id32/description5000；Message测试列title100/content2000/url200/type20；Dwz测试列orig1000/uri20/jiacn32。实施前须与权威建表/迁移定义核对，未据此新增硬门槛。

## 2. 逐接口定义：Dict 7条

身份为已验证JWT；表中权限为源码声明/目标保持，**未证明当前方法权限注解已启用**。GET/DELETE无body；无业务输入POST为Q<null>。所有JSON响应R，成功200/E0；通用失败见§4。

| 接口（目标method） | query / Q.data | R.data | 授权及实际差异 |
| --- | --- | --- | --- |
| GET /dict/get | id:D1必填 | DictView | dict-get；现行不存在返回null，目标不可见404/E001。 |
| POST /dict/create | DictCreate | null | dict-create；检查实际插入结果，不能忽略0行仍success。 |
| POST /dict/update | DictUpdate | null | dict-update；作用域内更新，0行须区分不存在和已有相同值。 |
| POST /dict/list | PageQuery<DictSearch> | PageView<DictView> | dict-list；现行null search可进入DAO空指针；目标正常空筛选。 |
| DELETE /dict/delete | id:D1必填 | null | dict-delete；不可见404；不吞删除结果。 |
| GET /dict/locale | 无业务参数，沿用LocaleContextHolder语言选择 | DictView[] | dict-locale；只返回许可范围内公共/本租户字典，不能从全局缓存泄露租户记录。 |
| POST /dict/cleanCache | null | null | 源码无方法权限声明；目标复用dict-update权限。当前只是修改第一条locale记录updateTime，没有删除Redis键；实际缓存失效方案仍见§5。 |

## 3. 逐接口定义：Dwz 7条、Msg 9条

| 接口（目标method） | query / Q.data | 响应业务值 | 身份/副作用 |
| --- | --- | --- | --- |
| GET /dwz/view/{uri:.+}/** | path字符串，保留路径后缀提取 | HTML重定向，无R | 公开导航；沿用失效/异常跳固定404页面。计数附带写入，保留GET。不能作为管理授权。 |
| GET /dwz/get | id:D1 | ShortLinkView | 用户JWT+资源可见性；缺失/不可见404/E001。 |
| POST /dwz/gen | ShortLinkCreate | String（短码，不是对象） | 用户JWT；以可信归属生成。原按jiacn+orig查重不等于持久幂等。 |
| POST /dwz/restore | query uri:String；data:null | String（原URL） | 用户JWT，保留现解码用途；view内部写pv/updateTime，因此改POST；不是纯读。 |
| POST /dwz/update | ShortLinkUpdate | ShortLinkView | 用户JWT+管理资源授权；事务后投影，不再回显输入。 |
| DELETE /dwz/delete | id:D1 | null | 用户JWT+管理资源授权；不忽略删除结果。 |
| POST /dwz/list | PageQuery<ShortLinkSearch> | PageView<ShortLinkView> | 用户JWT+管理范围；不能使用body租户过滤作认证。 |
| GET /msg/get | id:D1 | MessageView | 用户JWT、同tenant/client/收件人uid；缺失/不可见404/E001。 |
| POST /msg/create | MessageCreate | null | msg-create发送权限；收件人不是当前用户的同义字段。 |
| POST /msg/read | query id:D1；data:null | null | 同收件人范围；目标状态READ。 |
| POST /msg/unread | query id:D1；data:null | null | 同收件人范围；目标状态UNREAD。 |
| POST /msg/readall | query userId:D1；data:null | null | userId必须匹配可信uid；仅同tenant/client用户消息。原SQL不区分状态，会恢复DELETED，目标限定UNREAD→READ，不复活回收站。 |
| DELETE /msg/delete | id:D1 | null | 同收件人范围；永久删除，不把同Owner资格取消用于越权。 |
| POST /msg/recycle | query id:D1；data:null | null | 同收件人范围；目标状态DELETED。 |
| POST /msg/restore | query id:D1；data:null | null | 同收件人范围；原实现恢复为READ，不猜成UNREAD。 |
| POST /msg/list | PageQuery<MessageSearch> | PageView<MessageView> | 同收件人范围；不因缺省search.userId而扩大范围。 |

写动作query键保留id/userId/uri，不添加旧GET兼容。幂等不能只加header：消息固定状态设置可以同状态成功，但需在授权与当前行存在条件下判定；不得因0更新行就统一报失败。生成/创建/访问计数暂无持久请求去重证据，公共客户端不可自动重试；响应丢失视为未知，不能谎称未执行。尚未因此引入新幂等平台。

## 4. 共同失败与代码落点

目标：400/E003封闭字段/数值/分页/协议非法；401/E401无有效认证；403/E403已认证无操作权限或跨用户声明；404/E001资源不可见/不存在；409/E002已确认重复提交；422/E004非法URL（仅确实经过URL校验的分支）；500/E500内部/数据不变量故障。不将所有SQL异常映射重复，不把所有运行时异常转404。资源列表空集合200，单资源不存在不再200/null。

当前`ExceptionHandlerAdvice`部分异常HTTP200，AccessDenied映射401，不能视为目标已满足。调整既有JsonResult/异常边界；Controller只解Q/投影R，服务层复用类型化可信作用域；DAO谓词必须包含资源键+授权范围，不能先无锁get再无范围update。0行既可能不存在也可能同值，需同事务授权读取或明确驱动行数语义。

具体修改入口：DictController/DictServiceImpl/DictDaoImpl；DwzController/DwzServiceImpl/DwzRecordDaoImpl；MsgController/MsgServiceImpl/UserMsgDaoImpl；三个Entity只作持久模型，新边界DTO不能直接继承BaseEntity接收租户和时间。BaseDaoImpl不做无差别全局行为变更；为本域显式限定DAO。DTO沿模块core/api层定义，service适配，不倒置依赖。

## 5. 已发现而未全量闭合的源码风险

1. **认证不等于授权**：ResourceServerConfig的资源链只要求authenticated；仓内Java未找到EnableMethodSecurity/EnableGlobalMethodSecurity启用点。源码注解不能当已验证权限。应核实际装配和全部权限调用影响，不为修23条盲目全局启用所有注解。
2. **可信uid实际来源**：AccountSecurityJwtClaims解析用户JWT的uid；机器没有用户uid。现EsSecurityContextFilter未填uid，不能编造context.userId。适配层用已验证JWT解析结果生成类型化作用域；旧用户token只有jiacn时不能用请求userId补信任，需沿现账号服务核实映射/认证失败策略。业务service不读取SecurityContext。
3. **共享字典权限**：BaseDao的tenant=0可见逻辑、全局DICT_LIST缓存和写管理范围不能靠隐藏DTO字段解决。需确定公共字典只读与管理员写入边界、缓存按可信作用域隔离；cleanCache应真正失效对应缓存，不能继续借更新时间假装清理。当前没有证据说明这一设计已落地。
4. **短链接管理与公开导航不同**：selectByUri无租户范围是公开短码用途；不能机械给公开跳转加私有ACL。管理接口必须按可信主体隔离；gen去重也要纳入范围。原PV读加一写回会丢并发计数，不能因改POST宣布已解决。URL协议、expiry/status和nullable历史字段处理仍要核定。
5. **消息readall副作用**：UserMsgDaoImpl仅user_id谓词，既无租户/客户端又无UNREAD条件。目标添加三项范围与状态谓词是防越权、防回收站复活，不是新增外单功能。
6. **测试边界**：未检查实时数据库DDL/异常状态行，不宣称字段长度或状态值清洁；未运行Java/浏览器/正式Flow。该23条保留NOT_FIELD_CLOSED，不能从527未完全核定项中直接扣除23作为“完成”。

## 6. 已找到的消费者与传输拦截

- `ShortLink.vue`两条：create('/gen')保留POST；get('/restore')改POST+query+Q<null>。去掉onSuccess内code/data二次解包，直接使用String；jiacn不再作为业务授权输入，expireTime→expiresAt。保留错误处理，不吞失败。
- `stores/message.js`四个operation（list含普通/未读两调用）：read/readall/recycle改POST；DTO.items/total精确解析，不再猜data/list/records。message.id→messageId、时间→createdAt/updatedAt、状态改上述字符串，连同MessageCenter.vue模板和事件参数一起改。total/unreadTotal保留十进制String；加减/比较在边界用BigInt，结果回String，不能将权威计数转Number。身份切换generation、AbortSignal、clearMessageState不删。
- `/dict`在已查web/src及ops未找到实际网络调用；只能登记“本次搜索未命中”，不能宣布接口无消费者。上述六条关系及生产/消费文件hash加入manual-consumer-bindings.json。
- **sw.js不是普通资源fetch**：当前只排除/api/、/ws/前缀，其他同源GET会走cache-first；未检查响应private/no-store。若实际代理暴露/agent等路径，业务JSON可能被拦截缓存。目标限定静态资产集合/导航外壳，尊重响应缓存禁止指令，身份数据绝不缓存，清理本应用已过时缓存；不扩成596路由黑名单，不删除其他应用缓存。当前路由可达性/缓存存量未查，不声称线上已发生泄露。
- `JuyitingGame.js`的TMX/rawXML fetch为资源读取；`cdp-harness.mjs`的waitForJson为Chrome调试协议；`WxMpManager.runAction`中的request()是注入回调。它们不是各自新增一个CYF业务接口，仍须追到真实调用点才建立binding。

## 7. 认证/字典续核（2026-10-15，覆盖上述未闭合项的新增事实）

### 7.1 可信uid已找到，不能再称来源未知

`oauth/.../AccountSecurityJwtClaims`把USER token的uid按正Long十进制解析；`AccountSecurityJwtValidator`再调用`AccountSecurityService.findByUserId`核对jiacn/authEpoch/账号状态。LEGACY_USER现有路径调用`findUniqueByExactJiacn`且要求authEpoch=0；机器token无用户uid。`AccountSecurityTokenCustomizer`已在用户access token写入uid/jiacn/auth_epoch/client_id和版本化tenant claim。

目标适配：先要求通过现有JWT校验的用户主体；USER以解析的uid生成消息Scope，复用可信tenant解析及client_id；机器主体在消息用户接口403，不从body补uid。当前唯一协议的用户端采用USER token；如既有认证边界暂接受LEGACY_USER，只能复用上面的账号唯一映射取uid，不允许context缺uid时回退请求userId或0。该既有认证策略是否整体退役属于账号模块既定范围，本接口改造不另创旧业务报文兼容。service接类型化Scope，不依赖线程SecurityContext，也不改全局EsContext制造“默认uid”。

### 7.2 方法权限还存在第二个独立缺口

本轮仓内生产Java继续未发现方法安全启用器；不能宣称线上已开启或已关闭。更关键的是：ResourceServerConfig两条JWT链均使用默认jwt配置，未见本项目JWT authority converter；TokenCustomizer不写登录态的`module-func`权限。`DefaultSecurityConfig.userDetailsService`仅在登录加载PermsService.findByUserId，再按启用状态构造`module-func` authorities。**因此即使补EnableMethodSecurity，也不能假定JWT已具有dict-get/msg-create等authority**。

实施必须同时核定JWT权限来源和启用范围；不能只打开全局注解，造成跨模块所有被注解接口无权限。复用已有PermsService及同一启用权限投影，而非从客户端scope/请求字段拼权限、复制另一套角色库。可复用登录权限解析为user-api契约/user-service实现，oauth-resource适配只消费接口；禁止base-service倒依赖oauth-service。仍须核定权限DAO的tenant/client作用域后才能宣布授权闭合，不能把findByUserId名字当隔离证明。

### 7.3 字典HTTP与内部配置不得共用“全量可读”投影

实际链：DictServiceImpl.selectAll→Redis键DICT_LIST→BaseDaoImpl.selectAll→baseMapper.selectList(null)。后者没有selectByEntity的tenant条件；selectByEntity仅在非0租户时加`tenant=current OR tenant=0`，tenant=0本身不加任何范围条件。隐藏DTO.tenantId不构成隔离。Redis注入为required=false但selectAll无null保护，不能假设无Redis时自动回DB。

进一步发现内部调用`SmsReceiver`使用同一DictService读取`EMAIL_SERVER/PASSWORD`、`SMS_CONFIG/PASSWORD`等配置，实际值在name字段。故“locale按语言过滤全部字典”仍可能暴露服务配置；本轮只读源码、未读取生产密码或证明线上已泄漏。此前§2公共/本租户可读描述必须附加**业务字典可公开分类**，不能把tenant=0等同所有字段可公开。仅对password字符串做脱敏也不足以允许整个配置字典外发。

目标最小边界：HTTP查询调用专用作用域方法，不调用内部全量selectAll；业务字典投影与服务器配置读取分开，配置类型不通过locale/get/list序列化，内部发送服务仍走受控配置读取。CRUD写作用域明确tenant/client，租户0的共享数据不因任意tenant管理员而可改。需核完既有字典type分类及调用点才能冻结公开白名单；不凭测试样例把全部type自动批准公开。

缓存目标：HTTP专用投影键包含可信tenant/client及公开分类版本，语言选择可在同范围结果内过滤；公共数据与私有数据不要混成全局列表。cleanCache实际失效对应缓存，不再更新时间伪清理；字典CRUD成功后失效，回滚不提前报新缓存有效。内部配置缓存不直接复用浏览器投影。具体并发失效、公共数据修改影响所有租户缓存仍需核定，不用“删一个键”冒充完整方案。

这些是源码风险与实施约束，非应用修复。uid来源项已明确；权限DAO、字典公开分类/缓存失效仍未闭合，23条保持NOT_FIELD_CLOSED。无生产配置查询、权限开关修改或服务重启。

### 7.4 权限DAO已追到具体错字段，不再停在“需查DAO”

`PermsServiceImpl.findByUserId/findByRoleId`把`PermsRelEntity.getId()`赋给`PermsEntity.id`，但实体同时有独立`permsId`，SQL `PermsRelMapper.selectByUserId`返回的是`r.*`关系行。因此传给登录后续idList的实际是**关系记录ID而非权限ID**；二者偶然相等才碰巧正确。修正落点是这两个映射使用getPermsId、去重，再查询真实启用权限；不能把关系ID直接变成JWT authority。必须加入relation.id≠permsId的最小用例。

同SQL角色关联及group子查询只限定userId，没有tenant/client连接谓词；selectByRoleId也只有roleId。身份uid可信不等于授权关联范围已正确。目标在user-api明确`PermissionScope(tenantId,clientId,userId)`，mapper每个角色/用户组/权限关联均绑定该可信范围，资源数据写入权限不从“公开字典可读”继承；复用原角色/组并集语义，不另建权限系统。现有全局角色/权限目录的共享策略需要与既有初始化/迁移来源核对，不能未经证据直接补OR tenant=0放大授予。下一项已缩小为共享角色/目录边界，而不是uid或字段归属未知。

此缺陷来自源码，未核线上关系数据是否恰好对齐、未修改账号权限。统一开发时必须将权限来源适配、错字段修复和作用域条件放在同一候选验证，不能先全局启用注解再在发布验收时补救。

### 7.5 共享角色来源核对与迁移边界

已查`migration-tenant-default-zero.sql`：角色、组、权限及关系表只将旧null tenant统一填0并设置默认值；**这是数据默认化，不是跨tenant/client授予权限的证明**。`TenantScopeHelper`描述tenant0可见，但可见目录与可操作授权不能混用。

写入链也需同时对齐：RoleServiceImpl.changePerms创建关系只赋roleId/permsId；batchAddUser只额外设置来自role的clientId；GroupRelMapper.batchAdd的手写INSERT甚至没有tenant_id/client_id列。若只给读查询加严格scope，可能把原授予读空。具体实施应让上述写入口先在可信Scope下验证role/user/group/permission归属，再将同一scope显式传入新增/删除关联SQL；创建和读取同批切换，不从提交Entity取可信scope。已经存在但缺少可信归属的关系不能按请求用户自动补归属或放宽OR tenant0。此轮没有读生产角色关联，也没有授权重发/迁移真实权限，因此共享授予的历史归属不能声称已清洁。

这一发现明确了改动路径与风险，不等于新增全局权限项目：保留原角色/组关系模型及账户状态验证，不在接口规范统一时顺手扩展权限功能。未决为存量关联归属与实际启用范围的证据，不再含uid解析、relationId/permsId或源码写入位置的不确定性。
