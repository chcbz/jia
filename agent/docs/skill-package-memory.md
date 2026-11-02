# 技能包与描述内存职责

2026-11-02候选实现；基线17e8fc860f779594fcca9986e869c5353d7b2b8d。验证/合入/上线状态见CYF根仓 `docs/implementation/handoffs/JUYITING-PERFORMANCE-20261102.md`，本文不代表已上线。

- `SkillPackages.prepare`仅为真实下载准备操作内的包字节；磁盘/预置资源仍是来源，下载重新验证实际字节。不再保留应用级`byte[]`缓存，防御性复制不取消。
- 商城、报价/购买、目录、安装结果、岗位准备改用`describe`。缓存命中不读取/复制ZIP；首次或淘汰后仍需在事务外读取验证并投影描述。没有新增数据库结构、持久sidecar或对外协议。
- 描述缓存只含不可变清单、包大小，按内容摘要复用。商品、价格、权限、租户、安装状态来自每次调用的当前事实，不缓存授权结论。命中仍核对包大小及清单技能身份。
- 描述LRU权重预算为已有单包上限16MiB，约原诊断768MiB堆的2%；每条权重估算为1024 + 8 × 清单JSON字符数，以覆盖条目及树/字符串开销。它不是精确堆字节计量或请求/包大小门禁。超预算描述正常返回但不驻留，预算耗尽淘汰旧描述，不拒绝业务；预算0的回归证明业务可在无全局缓存下完成。
- 原生任务的命令发现、首次准入、效果开始复用`InstalledSkillDescriptorLookup.withPreparedRead`。实际实现仅在一次同步操作内固定包描述，事务内继续读取当前授权/安装事实；异常及嵌套退出在finally释放/恢复ThreadLocal，不跨请求保存状态，不用于异步跨线程传播。
- 岗位操作/购买/安装回执携带`Description`跨事务；下载携带本次包对象跨事务，保持原有重验。禁止在持锁事务里因缓存缺失读取整个ZIP：正确调用方须在锁外准备。缓存淘汰不使已经准备的操作失效。

## 风险与回归

| 风险/失败范围 | 责任边界 | 回归 |
| --- | --- | --- |
| 版本积累永久占用堆、目录反复复制ZIP | SkillPackages取消全包缓存，描述按权重淘汰 | SkillPackagesTest：热目录删除源文件后仍读描述，实际下载不能读旧内存包；LRU淘汰 |
| 淘汰导致事务中读包或随机失败 | 操作内固定描述、锁外准备 | 零预算事务、强制淘汰、嵌套与异常退出；SkillExecutionServiceTest三条原生链 |
| 内容摘要命中但大小/技能身份错误 | 描述边界验证当前产品与不可变清单 | 大小、skillKey/version错配，调用方修改JsonNode不污染缓存 |
| 缓存掩盖撤销/价格/安装变化 | 既有业务层每次核对，不缓存业务决定 | InstalledSkillDescriptorServiceTest、SkillMarketplaceRealTransactionTest、SkillManagementTransactionTest |
| 实际下载内容或安装对象变化 | 原下载授权/fence及完整性校验 | SkillPackagesTest、岗位/marketplace/management既有回归 |

不修改脱敏、Runtime历史轮询、GC配置或其他诊断候选。目录冷路径仍可优化，但不能把本次取消常驻缓存误述为所有路径零分配/零IO。
