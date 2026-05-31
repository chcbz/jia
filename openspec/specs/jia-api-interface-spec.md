# Jia API 接口文档索引

本文档按项目模块拆分接口 spec，每个模块文档包含请求方式、接口地址、入参明细、响应结构和模型字段字典。

| 模块 | 接口数 | 文档 |
| --- | ---: | --- |
| Base 基础模块 | 7 | [interfaces/jia-base-interface-spec.md](interfaces/jia-base-interface-spec.md) |
| Chat 聊天模块 | 6 | [interfaces/jia-chat-interface-spec.md](interfaces/jia-chat-interface-spec.md) |
| DWZ 短链接模块 | 7 | [interfaces/jia-dwz-interface-spec.md](interfaces/jia-dwz-interface-spec.md) |
| ISP 模块 | 61 | [interfaces/jia-isp-interface-spec.md](interfaces/jia-isp-interface-spec.md) |
| Kefu 客服模块 | 11 | [interfaces/jia-kefu-interface-spec.md](interfaces/jia-kefu-interface-spec.md) |
| Material 素材模块 | 39 | [interfaces/jia-material-interface-spec.md](interfaces/jia-material-interface-spec.md) |
| OAuth 模块 | 12 | [interfaces/jia-oauth-interface-spec.md](interfaces/jia-oauth-interface-spec.md) |
| Point 积分模块 | 14 | [interfaces/jia-point-interface-spec.md](interfaces/jia-point-interface-spec.md) |
| SMS 短信模块 | 17 | [interfaces/jia-sms-interface-spec.md](interfaces/jia-sms-interface-spec.md) |
| Task 任务模块 | 12 | [interfaces/jia-task-interface-spec.md](interfaces/jia-task-interface-spec.md) |
| User 用户模块 | 80 | [interfaces/jia-user-interface-spec.md](interfaces/jia-user-interface-spec.md) |
| Workflow 工作流模块 | 25 | [interfaces/jia-workflow-interface-spec.md](interfaces/jia-workflow-interface-spec.md) |
| WX 微信模块 | 57 | [interfaces/jia-wx-interface-spec.md](interfaces/jia-wx-interface-spec.md) |

## 全局约定

- `ANY` 表示源码使用 `@RequestMapping` 且未显式限制 HTTP method。
- 普通成功响应：`{ "code": "E0", "msg": "ok", "status": 200, "data": ... }`。
- 分页成功响应：`{ "code": "E0", "msg": "ok", "status": 200, "pageNum": 1, "total": 100, "data": [...] }`。
- 分页请求体：`{ "pageNum": 1, "pageSize": 20, "search": {...}, "extra_search": "...", "orderBy": "field desc" }`。
- 实体通用字段：`createTime`、`updateTime`、`tenantId`、`clientId`（继承 `BaseEntity` 的模型都可能返回）。
- 权限、认证、异常响应由项目统一配置处理，具体权限码可参考 Controller 中的 `@PreAuthorize`。
