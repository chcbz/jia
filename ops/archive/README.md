# 典籍数据发布（2026-10-23 候选）

适用：本目录同提交的通用案卷阁实现。完整基线、提交和验收状态见项目根 handoff `ARCHIVE-CONTENT-DECOUPLING-20261023`。本说明不是生产迁移/导入/部署授权；云端构建与数据库验收未完成前不得视为已发布。

## 数据与程序分离

- 数据库 `archive_work → archive_edition → archive_chapter → archive_paragraph` 是在线事实源。在线启动只初始化/核验结构，不加载书籍文件、不导入、不对整书逐段对账。
- 新增书籍只准备外部数据包并显式发布；无需修改 Java/Vue、重新打包或重启服务。书架查询数据库的 READY 活动版本。
- 正文/顺序/段落/标题变化发布新 `editionId`；READY 正文不可原地修改。`workId`、版本和段落 ID 稳定；既有 work 的书名不通过导入偷偷覆盖，书名管理不是此命令功能。
- 原整书 JSON 和书籍专属校验器仅留在 `src/test` 作为回归夹具，不进入生产库 JAR。通用程序不固定书名、来源摘要、120 回、引首或最后段落 ID。
- 导入继续使用 STAGING→校验→READY→活动指针切换；分批失败可能留下 STAGING，可按相同包和 SHA 重入。失败不等于数据库完全无写入，旧活动版本不受未完成导入替换。已完成版本只核验，不重复插入。

## 纯数据流程

1. 在仓库外维护 UTF-8 JSON（许可、来源归属和原稿一同保存在内容档案，不把正文提交回生产源码）：

```json
{
  "workId": "example-book",
  "editionId": "example-book-v1",
  "title": "示例典籍",
  "chapters": [
    {"title": "第一章", "paragraphs": ["第一段原文", "第二段原文"]}
  ]
}
```

`preface` 可省略/null，或为 `{"title":"序","paragraphs":["序言原文"]}`。至少一个章节，不固定上限为120；段落不做 trim/Unicode 归一化。ID 使用 ASCII 字母、数字、点、下划线、横线且首字符为字母或数字。工具校验书名/章名及重复字段。

2. `python3 ops/archive/prepare_content.py /private/content/book.json /private/content/manifest.json`。输出已存在会拒绝覆盖；标准输出是**文件 SHA-256**。准备只访问指定文件，不访问数据库/网络。
3. 使用经 Flow 验证的发行 JAR 独立校验（不启动 Spring/Web 服务）：

```sh
java -Dloader.main=cn.jia.chat.archive.content.ArchiveContentCommand \
  -cp /path/to/verified-api.jar org.springframework.boot.loader.launch.PropertiesLauncher \
  validate /private/content/manifest.json EXPECTED_FILE_SHA256
```

程序校验外部文件 SHA、内部规范化摘要、结构/计数、正文 UTF-8 字节及逐段 SHA。文件 SHA 与内部 `manifestSha256` 不是同一个值。

4. 在明确数据库目标、操作权限及恢复措施后，同一入口改为 `publish MANIFEST SHA PRIVATE_DB_PROPERTIES`。配置文件必须是非符号链接普通文件且无 group/other 权限（如0600），内容为 Java properties：`url=jdbc:mysql://...`、`user=...`、`password=...`。密码不放命令行或仓库。命令不会启动 HTTP 服务，**会写数据库**；失败输出不回显可能含凭据的 JDBC 异常。成功回执固定 work/edition/文件 SHA。
5. 以已有授权身份读取 `/archive/v1/works`、指定书籍目录和正文，确认新版本及用户数据隔离。不能只凭命令退出码宣称业务验收完成。

安装 JAR 的 PropertiesLauncher 类已只读确认存在；新候选命令仍需在本次 Flow 制品上做 validate smoke，不能用历史安装包执行新增入口。

## 新的唯一接口契约

沿用 `docs/interface-standard.md` v1.2（2026-10-14）及当前 JsonResult 信封；以下为 data/请求业务字段，不另建信封。

- `GET /archive/v1/works` → `{"items":[{"workId":"example-book","title":"示例典籍","editionId":"example-book-v1","chapterCount":1}]}`；无书返回空 items。沿用精确认证、feature scope、private/no-store。
- `GET /archive/v1/catalog?workId=example-book`，`workId` 必需；不再有隐式默认书。`activeEdition.preface` 可为 null。edition/block 接口保持版本定位。
- 提问 PUT 请求现在必需 `{ "editionId":"example-book-v1", "question":"…", "anchor":{…} }`。服务端按显式版本验证活动指针和选文，禁止跨书锚点；锚点结构不变。
- 完成阅读按该版本最后一章最后一段末 UTF-8 字节判定。
- 切书先保存旧书进度，隔离旧请求回包；未解决的保存操作不能静默丢弃。身份切换仍清理本人数据和在途状态。

## 已有库的一次性升级

源码迁移：`chat/jia-chat-mapper/src/main/resources/db/archive-multi-work-migration.sql`。只放宽两项书籍专属 CHECK，保留 FK、唯一键、总数/字节一致性及 READY 不变式。

**不可让新版应用自动迁移生产**：先授权并备份两表 SHOW CREATE TABLE/数据、记录现有版本和恢复方案；在发布维护窗口执行两个 ALTER 并核验，再部署新版前后端。MySQL DDL 隐式提交，两个 ALTER 之间失败要如实记录并恢复/补齐，不能宣称整个文件是原子事务。旧应用的严格 schema 校验可能拒绝新约束；回滚应用前必须核对 schema，新增非120章/无引首数据后不能直接恢复旧 CHECK。不得删除书籍或用户阅读数据来“兼容回滚”。

这次解耦仅移除启动时的内容工作；全后端60秒启动目标须另做冷/热启动实测，不能据代码删除量认定达标。
