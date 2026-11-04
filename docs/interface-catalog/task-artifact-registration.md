# 事项成果显式登记（2026-11-04，实施候选）

遵循统一接口规范1.4，不表示已部署。下载复用既有deliverables固定版本content接口；新增登记端点：

`POST /agent/personal-workspace/task-artifact-registrations`

请求Q.data：`{taskId,artifactId,artifactVersion,contentSha256,filename}`。ID为原字符串，版本为正十进制字符串（<=2147483647），摘要64位小写hex，filename为255字符以内的有效文件名/显示名提示，不接受字节、owner、tenant、存储路径或任意URL。

Idempotency-Key=`task-artifact-`+SHA256(UTF8(taskId+LF+artifactId+LF+artifactVersion))；所有标识禁止控制字符。重试保持原key和相同请求。业务主体来自既有认证上下文；资源权限复用openContentForTaskOwner，在服务端校验实际摘要及固定版本；内容流式归档到现有个人文件存储，存储去重由既有适配器负责，不经过浏览器下载/上传。不承诺不同存储根零物理副本。

成功200 R.data为既有PersonalWorkspaceWire.UploadView，operation.state=COMMITTED，file.originKind=AGENT_DELIVERY，file/operation/version一致；version.sha256为固定成果摘要。重复登记返回相同fileId及原登记版本。名称变更不是重试，同key变更filename409；任务/版本/摘要不能换成最新版本。若文件已回收，返回TRASHED，UI提示用户自行恢复，不自动恢复或新建副本。接口不修改事项成果/验收事实，不新增业务表/DDL/CHECK，不触发Agent执行。

错误：400字段/确定性key不合法；404事项或成果不可见（复用Owner ACL）；409摘要/版本/幂等冲突；503内容存储不可用/操作尚未确认。网络错误未知：UI保留不可变请求并显示“核对保存结果”，仅显式重试，复用既有操作幂等回执；COMMITTED之前不显示已保存。

责任层/风险：认证入口提取主体；内容服务保证任务Owner授权/读取完整性；个人资料服务负责显式登记、固定摘要匹配及幂等，既有writes事务持久化。Web只展示和提交固定引用，不作安全隔离。跨Owner不写入、错误摘要不写入、重复登记不重复文件、旧版本下载不替换新版本为必要回归。入口PersonalWorkspaceServiceImplTest、PersonalWorkspaceHttpPersistenceIntegrationTest、Web juyiting-completed-records.test.js与task-artifact-registration.test.js。

默认事项成果只关联事项，用户需要复用时点击“保存”。现有旧Runtime原生文件提交有自动归档分支，本次不改Runtime执行协议，不把新增显式登记宣称为所有旧链路已收口。
