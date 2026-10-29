# Jackson 3 收敛（2026-10-29）

## 范围与契约

本轮迁移 Agent/Chat 自有的技能、严格请求、归档、推荐与流式消息 JSON，以及对应测试；删除两个业务模块显式声明的 Jackson 2 databind。唯一 Owner 为当前会话，不改变接口、ACL、冻结 fixture、已持久化摘要/幂等事实或业务能力。

统一使用 `tools.jackson`。依赖目录对齐现有运行基线 Jackson 3.1.4，注解仍为 `com.fasterxml.jackson.annotation` / `jackson-annotations:2.21`，不是遗留的 Jackson 2 解析器。迁移使用 Jackson 3 原生不可变 builder；`builderWithJackson2Defaults()` 仅保留已有 JSON 的排序、数字与反序列化默认语义，不加载/回退到 Jackson 2。显式的重复键、尾随内容、严格类型规则保留。JSON 异常由 IOException 改为 JacksonException 的位置同步转换，真实文件 IO 异常仍按 IO 处理。

## 第三方核验：全运行时删除仍被阻塞

2026-10-29 直接读取 Maven Central 发布 POM（不是仅根据博客或版本号推断）：

| 组件 | 核对的已发布版本 | 结果及本次处理 |
| --- | --- | --- |
| OpenAI Java core | 4.94.0 | 仍直接依赖 Jackson 2 core/databind/JDK8/JSR310/Kotlin；不能用升级该版本完成移除 |
| Springdoc common | 3.2.0 | 仍直接依赖 Jackson 2 databind/YAML/JSR310；不能强制排除 |
| Swagger core Jakarta | 2.2.55 | 仍直接依赖 Jackson 2 databind/YAML/JSR310 |
| Elasticsearch Java | 9.5.5 | 仍直接依赖 Jackson 2 与 3；现有 9.2.2 已有 Jackson3JsonpMapper，生产已加载该适配器 |
| Redisson | 4.8.0 | 含两代可选格式依赖；现有 4.2.0 已有 Jackson3 codecs，现场加载 Kryo5，不因清理未使用的旧 codec 改写 Redis 存储格式 |
| 微信 MP | 4.8.6-20261024.175727 | Jackson2 XML 标为 optional；需要按真实功能路径进一步核查，不能据 optional 宣称运行不依赖 |

已部署 JAR 字节码扫描也发现 OpenAI、Springdoc/Swagger、微信、Elasticsearch 等真实 Jackson 2 符号引用。Spring/Security/Redis 等的可选适配类同样有引用；“类中有引用”与“线上调用了该路径”分别记录，不机械删除。

没有采用全局 exclude，没有将第三方升级到仍无法达成目标的版本，也没有删除 AI/微信/OpenAPI 功能。彻底移除需要受支持的上游版本，或另行确认 SDK/文档组件替换、维护分支等实际扩大范围的方案；不能把此次自有代码迁移宣称为全部完成。OOM 收益需实测，不承诺能够单独解决192MiB元空间耗尽。

## 验证及证据

- 源码静态扫描：自有 Java 不再引用 Jackson 2 core/databind 等，保留共享注解。
- 回归入口：既有 skillManifestContract（新增严格边界/小数精度测试）、skillManagementContract（冻结摘要与正反例）、Chat archive/动作/流式、推荐、认证测试；正式编译/应用回归和最终 JAR 在 Flow 固定源码运行，遵守600秒上限。
- 实施、CI/部署真实状态见根仓 `docs/implementation/handoffs/CODEX-NATIVE-SKILLS-20261027.md`。本文件不是上线或全依赖删除证明。
- 原始 POM、元数据及部署包字节码扫描：`/home/isp/state/cyf-native-skills-20261028/jackson3/`；不含凭证。
