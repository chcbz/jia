# User 用户模块接口文档

来源模块：`user`

本文面向前端对接，每个接口包含请求方式、地址、参数位置、必填性、请求体字段和响应结构。

## 通用响应结构

- 单对象或操作结果：`JsonResult<T>`，字段：`code`、`msg`、`status`、`location`、`data`。
- 分页列表：`JsonResultPage<T>`，字段：`code`、`msg`、`status`、`pageNum`、`total`、`data`（数组）。
- 成功码默认为 `E0`，HTTP 状态通常为 `200`。

## 接口汇总

| 请求方式 | 地址 | Controller | 响应 |
| --- | --- | --- | --- |
| GET | `/group/get` | `GroupController.findById` | JsonResult<[GroupVO](#model-groupvo)> |
| POST | `/group/get/users` | `GroupController.findUsers` | JsonResultPage<[UserEntity](#model-userentity)> |
| POST | `/group/get/roles` | `GroupController.findRoles` | JsonResultPage<[RoleEntity](#model-roleentity)> |
| POST | `/group/create` | `GroupController.create` | JsonResult<Void> |
| POST | `/group/update` | `GroupController.update` | JsonResult<Void> |
| DELETE | `/group/delete` | `GroupController.delete` | JsonResult<Void> |
| POST | `/group/list` | `GroupController.list` | JsonResultPage<[GroupEntity](#model-groupentity)> |
| POST | `/group/users/add` | `GroupController.userBatchAdd` | JsonResult<Void> |
| POST | `/group/users/del` | `GroupController.userBatchDel` | JsonResult<Void> |
| POST | `/group/role/change` | `GroupController.changeRole` | JsonResult<Void> |
| GET | `/login/index.html` | `LoginController.login` | ModelAndView |
| GET | `/login/sms/gen` | `LoginController.gen` | JsonResult<String> |
| GET | `/login/register` | `LoginController.register` | ModelAndView |
| POST | `/login/register` | `LoginController.register` | ModelAndView |
| GET | `/login/resetPassword` | `LoginController.resetPassword` | ModelAndView |
| POST | `/login/resetPassword` | `LoginController.resetPassword` | ModelAndView |
| GET | `/login/user` | `LoginController.info` | Principal |
| GET | `/login/user/info` | `LoginController.userInfo` | JsonResult<LdapUser> |
| POST | `/login/user/info/update` | `LoginController.updateUser` | JsonResult<LdapUser> |
| GET | `/login/user/password/change` | `LoginController.changePassword` | JsonResult<Void> |
| POST | `/login/user/avatar/update` | `LoginController.updateAvatar` | JsonResult<Void> |
| GET | `/msg/get` | `MsgController.findById` | JsonResult<[MsgEntity](#model-msgentity)> |
| POST | `/msg/create` | `MsgController.create` | JsonResult<Void> |
| GET | `/msg/read` | `MsgController.read` | JsonResult<Void> |
| GET | `/msg/unread` | `MsgController.unread` | JsonResult<Void> |
| GET | `/msg/readall` | `MsgController.readAll` | JsonResult<Void> |
| DELETE | `/msg/delete` | `MsgController.delete` | JsonResult<Void> |
| GET | `/msg/recycle` | `MsgController.recycle` | JsonResult<Void> |
| GET | `/msg/restore` | `MsgController.restore` | JsonResult<Void> |
| POST | `/msg/list` | `MsgController.list` | JsonResultPage<[MsgEntity](#model-msgentity)> |
| GET | `/org/get` | `OrgController.findAllById` | JsonResult<[OrgVO](#model-orgvo)> |
| GET | `/org/get/parent` | `OrgController.findParent` | JsonResult<[OrgEntity](#model-orgentity)> |
| GET | `/org/get/name` | `OrgController.findNameById` | String |
| POST | `/org/get/users` | `OrgController.findUsers` | JsonResultPage<[UserEntity](#model-userentity)> |
| POST | `/org/create` | `OrgController.create` | JsonResult<Void> |
| POST | `/org/update` | `OrgController.update` | JsonResult<Void> |
| DELETE | `/org/delete` | `OrgController.delete` | JsonResult<Void> |
| POST | `/org/list` | `OrgController.list` | JsonResultPage<[OrgEntity](#model-orgentity)> |
| POST | `/org/list/sub` | `OrgController.listSub` | JsonResultPage<[OrgEntity](#model-orgentity)> |
| POST | `/org/users/add` | `OrgController.userBatchAdd` | JsonResult<Void> |
| POST | `/org/users/del` | `OrgController.userBatchDel` | JsonResult<Void> |
| GET | `/org/get/director` | `OrgController.findDirector` | JsonResult<[UserVO](#model-uservo)> |
| POST | `/org/update/logo` | `OrgController.updateLogo` | JsonResult<[OrgEntity](#model-orgentity)> |
| GET | `/action/get` | `PermsController.findById` | JsonResult<[PermsEntity](#model-permsentity)> |
| POST | `/action/create` | `PermsController.create` | JsonResult<Void> |
| POST | `/action/update` | `PermsController.update` | JsonResult<Void> |
| POST | `/action/refresh` | `PermsController.refresh` | JsonResult<Void> |
| DELETE | `/action/delete` | `PermsController.delete` | JsonResult<Void> |
| POST | `/action/list` | `PermsController.list` | JsonResultPage<[PermsEntity](#model-permsentity)> |
| GET | `/role/get` | `RoleController.findById` | JsonResult<[RoleEntity](#model-roleentity)> |
| POST | `/role/get/users` | `RoleController.findUsers` | JsonResultPage<[UserEntity](#model-userentity)> |
| POST | `/role/get/perms` | `RoleController.findPerms` | JsonResultPage<[PermsEntity](#model-permsentity)> |
| POST | `/role/create` | `RoleController.create` | JsonResult<Void> |
| POST | `/role/update` | `RoleController.update` | JsonResult<Void> |
| DELETE | `/role/delete` | `RoleController.delete` | JsonResult<Void> |
| POST | `/role/list` | `RoleController.list` | JsonResultPage<[RoleEntity](#model-roleentity)> |
| POST | `/role/perms/change` | `RoleController.changePerms` | JsonResult<Void> |
| POST | `/role/users/add` | `RoleController.userBatchAdd` | JsonResult<Void> |
| POST | `/role/users/del` | `RoleController.userBatchDel` | JsonResult<Void> |
| GET | `/user/get` | `UserController.find` | JsonResult<[UserVO](#model-uservo)> |
| GET | `/user/get/name` | `UserController.findNameById` | String |
| POST | `/user/get/roles` | `UserController.findRoles` | JsonResultPage<[RoleEntity](#model-roleentity)> |
| GET | `/user/get/orgs` | `UserController.findOrgs` | JsonResult<List<[OrgEntity](#model-orgentity)>> |
| GET | `/user/check` | `UserController.check` | JsonResult<user == null ? 0 : 1> |
| POST | `/user/create` | `UserController.create` | JsonResult<user> |
| POST | `/user/update` | `UserController.update` | JsonResult<user> |
| POST | `/user/sync` | `UserController.sync` | JsonResult<Void> |
| DELETE | `/user/delete` | `UserController.delete` | JsonResult<Void> |
| POST | `/user/list` | `UserController.list` | JsonResultPage<[UserVO](#model-uservo)> |
| POST | `/user/search` | `UserController.search` | JsonResultPage<[UserEntity](#model-userentity)> |
| GET | `/user/point/change` | `UserController.changePoint` | JsonResult<Void> |
| POST | `/user/role/change` | `UserController.changeRole` | JsonResult<Void> |
| POST | `/user/group/change` | `UserController.changeGroup` | JsonResult<Void> |
| POST | `/user/org/change` | `UserController.changeOrg` | JsonResult<Void> |
| GET | `/user/my` | `UserController.userInfo` | JsonResult<[UserEntity](#model-userentity)> |
| GET | `/user/password/change` | `UserController.changePassword` | JsonResult<Void> |
| GET | `/user/password/reset` | `UserController.resetPassword` | JsonResult<Void> |
| POST | `/user/batch/import` | `UserController.batchImport` | JsonResult<Void> |
| GET | `/user/position/change` | `UserController.changePosition` | JsonResult<Void> |
| POST | `/user/update/avatar` | `UserController.updateAvatar` | JsonResult<[UserEntity](#model-userentity)> |

## 接口明细

### GroupController `/group`

源码：`user/jia-user-service/src/main/java/cn/jia/user/api/GroupController.java`

#### GET `/group/get`

- 处理方法：`GroupController.findById`
- 响应：[JsonResult](#model-jsonresult)<[GroupVO](#model-groupvo)>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `id` | query | yes | Long | 查询/表单参数 |

响应 data 说明：
- 按 `JsonResult<GroupVO>` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

#### POST `/group/get/users`

- 处理方法：`GroupController.findUsers`
- 响应：[JsonResult](#model-jsonresult)<Void>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `page` | body | yes | JsonRequestPage<[GroupEntity](#model-groupentity)> | 请求体 |

请求体字段：

[GroupEntity](#model-groupentity)

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `createTime` | `Long` | 源自 `GroupEntity` |
| `updateTime` | `Long` | 源自 `GroupEntity` |
| `tenantId` | `String` | 源自 `GroupEntity` |
| `clientId` | `String` | 源自 `GroupEntity` |
| `id` | `Long` | 源自 `GroupEntity` |
| `name` | `String` | 源自 `GroupEntity` |
| `code` | `String` | 源自 `GroupEntity` |
| `remark` | `String` | 源自 `GroupEntity` |
| `status` | `Integer` | 源自 `GroupEntity` |

响应 data 说明：
- 成功时通常无 `data` 或 `data=null`。

#### POST `/group/get/roles`

- 处理方法：`GroupController.findRoles`
- 响应：[JsonResult](#model-jsonresult)<Void>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `page` | body | yes | JsonRequestPage<[GroupEntity](#model-groupentity)> | 请求体 |

请求体字段：

[GroupEntity](#model-groupentity)

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `createTime` | `Long` | 源自 `GroupEntity` |
| `updateTime` | `Long` | 源自 `GroupEntity` |
| `tenantId` | `String` | 源自 `GroupEntity` |
| `clientId` | `String` | 源自 `GroupEntity` |
| `id` | `Long` | 源自 `GroupEntity` |
| `name` | `String` | 源自 `GroupEntity` |
| `code` | `String` | 源自 `GroupEntity` |
| `remark` | `String` | 源自 `GroupEntity` |
| `status` | `Integer` | 源自 `GroupEntity` |

响应 data 说明：
- 成功时通常无 `data` 或 `data=null`。

#### POST `/group/create`

- 处理方法：`GroupController.create`
- 响应：[JsonResult](#model-jsonresult)<Void>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `group` | body | yes | [GroupEntity](#model-groupentity) | 请求体 |

请求体字段：

[GroupEntity](#model-groupentity)

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `createTime` | `Long` | 源自 `GroupEntity` |
| `updateTime` | `Long` | 源自 `GroupEntity` |
| `tenantId` | `String` | 源自 `GroupEntity` |
| `clientId` | `String` | 源自 `GroupEntity` |
| `id` | `Long` | 源自 `GroupEntity` |
| `name` | `String` | 源自 `GroupEntity` |
| `code` | `String` | 源自 `GroupEntity` |
| `remark` | `String` | 源自 `GroupEntity` |
| `status` | `Integer` | 源自 `GroupEntity` |

响应 data 说明：
- 成功时通常无 `data` 或 `data=null`。

#### POST `/group/update`

- 处理方法：`GroupController.update`
- 响应：[JsonResult](#model-jsonresult)<Void>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `group` | body | yes | [GroupEntity](#model-groupentity) | 请求体 |

请求体字段：

[GroupEntity](#model-groupentity)

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `createTime` | `Long` | 源自 `GroupEntity` |
| `updateTime` | `Long` | 源自 `GroupEntity` |
| `tenantId` | `String` | 源自 `GroupEntity` |
| `clientId` | `String` | 源自 `GroupEntity` |
| `id` | `Long` | 源自 `GroupEntity` |
| `name` | `String` | 源自 `GroupEntity` |
| `code` | `String` | 源自 `GroupEntity` |
| `remark` | `String` | 源自 `GroupEntity` |
| `status` | `Integer` | 源自 `GroupEntity` |

响应 data 说明：
- 成功时通常无 `data` 或 `data=null`。

#### DELETE `/group/delete`

- 处理方法：`GroupController.delete`
- 响应：[JsonResult](#model-jsonresult)<Void>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `id` | query | yes | Long | 查询/表单参数 |

响应 data 说明：
- 成功时通常无 `data` 或 `data=null`。

#### POST `/group/list`

- 处理方法：`GroupController.list`
- 响应：[JsonResult](#model-jsonresult)<Void>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `page` | body | yes | JsonRequestPage<[GroupEntity](#model-groupentity)> | 请求体 |

请求体字段：

[GroupEntity](#model-groupentity)

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `createTime` | `Long` | 源自 `GroupEntity` |
| `updateTime` | `Long` | 源自 `GroupEntity` |
| `tenantId` | `String` | 源自 `GroupEntity` |
| `clientId` | `String` | 源自 `GroupEntity` |
| `id` | `Long` | 源自 `GroupEntity` |
| `name` | `String` | 源自 `GroupEntity` |
| `code` | `String` | 源自 `GroupEntity` |
| `remark` | `String` | 源自 `GroupEntity` |
| `status` | `Integer` | 源自 `GroupEntity` |

响应 data 说明：
- 成功时通常无 `data` 或 `data=null`。

#### POST `/group/users/add`

- 处理方法：`GroupController.userBatchAdd`
- 响应：[JsonResult](#model-jsonresult)<Void>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `group` | body | yes | [GroupVO](#model-groupvo) | 请求体 |

请求体字段：

[GroupVO](#model-groupvo)

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `createTime` | `Long` | 源自 `GroupVO` |
| `updateTime` | `Long` | 源自 `GroupVO` |
| `tenantId` | `String` | 源自 `GroupVO` |
| `clientId` | `String` | 源自 `GroupVO` |
| `id` | `Long` | 源自 `GroupVO` |
| `name` | `String` | 源自 `GroupVO` |
| `code` | `String` | 源自 `GroupVO` |
| `remark` | `String` | 源自 `GroupVO` |
| `status` | `Integer` | 源自 `GroupVO` |
| `nameLike` | `String` | 源自 `GroupVO` |
| `codeLike` | `String` | 源自 `GroupVO` |
| `remarkLike` | `String` | 源自 `GroupVO` |
| `userIds` | `List<Long>` | 源自 `GroupVO` |
| `roleIds` | `List<Long>` | 源自 `GroupVO` |

响应 data 说明：
- 成功时通常无 `data` 或 `data=null`。

#### POST `/group/users/del`

- 处理方法：`GroupController.userBatchDel`
- 响应：[JsonResult](#model-jsonresult)<Void>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `group` | body | yes | [GroupVO](#model-groupvo) | 请求体 |

请求体字段：

[GroupVO](#model-groupvo)

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `createTime` | `Long` | 源自 `GroupVO` |
| `updateTime` | `Long` | 源自 `GroupVO` |
| `tenantId` | `String` | 源自 `GroupVO` |
| `clientId` | `String` | 源自 `GroupVO` |
| `id` | `Long` | 源自 `GroupVO` |
| `name` | `String` | 源自 `GroupVO` |
| `code` | `String` | 源自 `GroupVO` |
| `remark` | `String` | 源自 `GroupVO` |
| `status` | `Integer` | 源自 `GroupVO` |
| `nameLike` | `String` | 源自 `GroupVO` |
| `codeLike` | `String` | 源自 `GroupVO` |
| `remarkLike` | `String` | 源自 `GroupVO` |
| `userIds` | `List<Long>` | 源自 `GroupVO` |
| `roleIds` | `List<Long>` | 源自 `GroupVO` |

响应 data 说明：
- 成功时通常无 `data` 或 `data=null`。

#### POST `/group/role/change`

- 处理方法：`GroupController.changeRole`
- 响应：[JsonResult](#model-jsonresult)<Void>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `group` | body | yes | [GroupVO](#model-groupvo) | 请求体 |

请求体字段：

[GroupVO](#model-groupvo)

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `createTime` | `Long` | 源自 `GroupVO` |
| `updateTime` | `Long` | 源自 `GroupVO` |
| `tenantId` | `String` | 源自 `GroupVO` |
| `clientId` | `String` | 源自 `GroupVO` |
| `id` | `Long` | 源自 `GroupVO` |
| `name` | `String` | 源自 `GroupVO` |
| `code` | `String` | 源自 `GroupVO` |
| `remark` | `String` | 源自 `GroupVO` |
| `status` | `Integer` | 源自 `GroupVO` |
| `nameLike` | `String` | 源自 `GroupVO` |
| `codeLike` | `String` | 源自 `GroupVO` |
| `remarkLike` | `String` | 源自 `GroupVO` |
| `userIds` | `List<Long>` | 源自 `GroupVO` |
| `roleIds` | `List<Long>` | 源自 `GroupVO` |

响应 data 说明：
- 成功时通常无 `data` 或 `data=null`。

### LoginController `/login`

源码：`user/jia-user-service/src/main/java/cn/jia/user/api/LoginController.java`

#### GET `/login/index.html`

- 处理方法：`LoginController.login`
- 响应：ModelAndView

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| - | - | - | - | 无入参 |

响应 data 说明：
- 按 `ModelAndView` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

#### GET `/login/sms/gen`

- 处理方法：`LoginController.gen`
- 响应：[JsonResult](#model-jsonresult)<String>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `phone` | query | yes | String | 查询/表单参数 |
| `smsType` | query | yes | Integer | 查询/表单参数 |

响应 data 说明：
- 按 `JsonResult<String>` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

#### GET `/login/register`

- 处理方法：`LoginController.register`
- 响应：ModelAndView

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| - | - | - | - | 无入参 |

响应 data 说明：
- 按 `ModelAndView` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

#### POST `/login/register`

- 处理方法：`LoginController.register`
- 响应：ModelAndView

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `user` | query/form | yes | LdapUser | 查询/表单参数 |

响应 data 说明：
- 按 `ModelAndView` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

#### GET `/login/resetPassword`

- 处理方法：`LoginController.resetPassword`
- 响应：ModelAndView

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| - | - | - | - | 无入参 |

响应 data 说明：
- 按 `ModelAndView` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

#### POST `/login/resetPassword`

- 处理方法：`LoginController.resetPassword`
- 响应：ModelAndView

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `phone` | query | yes | String | 查询/表单参数 |
| `smsCode` | query | yes | String | 查询/表单参数 |
| `newPassword` | query | yes | String | 查询/表单参数 |

响应 data 说明：
- 按 `ModelAndView` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

#### GET `/login/user`

- 处理方法：`LoginController.info`
- 响应：Principal

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `user` | query/form | yes | Principal | 查询/表单参数 |

响应 data 说明：
- 按 `Principal` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

#### GET `/login/user/info`

- 处理方法：`LoginController.userInfo`
- 响应：[JsonResult](#model-jsonresult)<LdapUser>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| - | - | - | - | 无入参 |

响应 data 说明：
- 按 `JsonResult<LdapUser>` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

#### POST `/login/user/info/update`

- 处理方法：`LoginController.updateUser`
- 响应：[JsonResult](#model-jsonresult)<LdapUser>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `user` | query/form | yes | LdapUser | 查询/表单参数 |

响应 data 说明：
- 按 `JsonResult<LdapUser>` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

#### GET `/login/user/password/change`

- 处理方法：`LoginController.changePassword`
- 响应：[JsonResult](#model-jsonresult)<Void>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `oldPassword` | query | yes | String | 查询/表单参数 |
| `newPassword` | query | yes | String | 查询/表单参数 |

响应 data 说明：
- 成功时通常无 `data` 或 `data=null`。

#### POST `/login/user/avatar/update`

- 处理方法：`LoginController.updateAvatar`
- 响应：[JsonResult](#model-jsonresult)<Void>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `file` | multipart | yes | MultipartFile | 文件/表单参数 |

请求体字段：

响应 data 说明：
- 成功时通常无 `data` 或 `data=null`。

### MsgController `/msg`

源码：`user/jia-user-service/src/main/java/cn/jia/user/api/MsgController.java`

#### GET `/msg/get`

- 处理方法：`MsgController.findById`
- 响应：[JsonResult](#model-jsonresult)<[MsgEntity](#model-msgentity)>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `id` | query | yes | Long | 查询/表单参数 |

响应 data 说明：
- 按 `JsonResult<MsgEntity>` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

#### POST `/msg/create`

- 处理方法：`MsgController.create`
- 响应：[JsonResult](#model-jsonresult)<Void>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `msg` | body | yes | [MsgEntity](#model-msgentity) | 请求体 |

请求体字段：

[MsgEntity](#model-msgentity)

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `createTime` | `Long` | 源自 `MsgEntity` |
| `updateTime` | `Long` | 源自 `MsgEntity` |
| `tenantId` | `String` | 源自 `MsgEntity` |
| `clientId` | `String` | 源自 `MsgEntity` |
| `id` | `Long` | 源自 `MsgEntity` |
| `title` | `String` | 源自 `MsgEntity` |
| `content` | `String` | 源自 `MsgEntity` |
| `url` | `String` | 源自 `MsgEntity` |
| `type` | `String` | 源自 `MsgEntity` |
| `userId` | `Long` | 源自 `MsgEntity` |
| `status` | `Integer` | 源自 `MsgEntity` |

响应 data 说明：
- 成功时通常无 `data` 或 `data=null`。

#### GET `/msg/read`

- 处理方法：`MsgController.read`
- 响应：[JsonResult](#model-jsonresult)<Void>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `id` | query | yes | Long | 查询/表单参数 |

响应 data 说明：
- 成功时通常无 `data` 或 `data=null`。

#### GET `/msg/unread`

- 处理方法：`MsgController.unread`
- 响应：[JsonResult](#model-jsonresult)<Void>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `id` | query | yes | Long | 查询/表单参数 |

响应 data 说明：
- 成功时通常无 `data` 或 `data=null`。

#### GET `/msg/readall`

- 处理方法：`MsgController.readAll`
- 响应：[JsonResult](#model-jsonresult)<Void>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `userId` | query | yes | Long | 查询/表单参数 |

响应 data 说明：
- 成功时通常无 `data` 或 `data=null`。

#### DELETE `/msg/delete`

- 处理方法：`MsgController.delete`
- 响应：[JsonResult](#model-jsonresult)<Void>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `id` | query | yes | Long | 查询/表单参数 |

响应 data 说明：
- 成功时通常无 `data` 或 `data=null`。

#### GET `/msg/recycle`

- 处理方法：`MsgController.recycle`
- 响应：[JsonResult](#model-jsonresult)<Void>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `id` | query | yes | Long | 查询/表单参数 |

响应 data 说明：
- 成功时通常无 `data` 或 `data=null`。

#### GET `/msg/restore`

- 处理方法：`MsgController.restore`
- 响应：[JsonResult](#model-jsonresult)<Void>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `id` | query | yes | Long | 查询/表单参数 |

响应 data 说明：
- 成功时通常无 `data` 或 `data=null`。

#### POST `/msg/list`

- 处理方法：`MsgController.list`
- 响应：[JsonResult](#model-jsonresult)<Void>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `page` | body | yes | JsonRequestPage<[MsgEntity](#model-msgentity)> | 请求体 |

请求体字段：

[MsgEntity](#model-msgentity)

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `createTime` | `Long` | 源自 `MsgEntity` |
| `updateTime` | `Long` | 源自 `MsgEntity` |
| `tenantId` | `String` | 源自 `MsgEntity` |
| `clientId` | `String` | 源自 `MsgEntity` |
| `id` | `Long` | 源自 `MsgEntity` |
| `title` | `String` | 源自 `MsgEntity` |
| `content` | `String` | 源自 `MsgEntity` |
| `url` | `String` | 源自 `MsgEntity` |
| `type` | `String` | 源自 `MsgEntity` |
| `userId` | `Long` | 源自 `MsgEntity` |
| `status` | `Integer` | 源自 `MsgEntity` |

响应 data 说明：
- 成功时通常无 `data` 或 `data=null`。

### OrgController `/org`

源码：`user/jia-user-service/src/main/java/cn/jia/user/api/OrgController.java`

#### GET `/org/get`

- 处理方法：`OrgController.findAllById`
- 响应：[JsonResult](#model-jsonresult)<[OrgVO](#model-orgvo)>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `id` | query | yes | Long | 查询/表单参数 |

响应 data 说明：
- 按 `JsonResult<OrgVO>` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

#### GET `/org/get/parent`

- 处理方法：`OrgController.findParent`
- 响应：[JsonResult](#model-jsonresult)<[OrgEntity](#model-orgentity)>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `id` | query | yes | Long | 查询/表单参数 |

响应 data 说明：
- 按 `JsonResult<OrgEntity>` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

#### GET `/org/get/name`

- 处理方法：`OrgController.findNameById`
- 响应：String

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `id` | query | yes | Long | 查询/表单参数 |

响应 data 说明：
- 按 `String` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

#### POST `/org/get/users`

- 处理方法：`OrgController.findUsers`
- 响应：[JsonResult](#model-jsonresult)<Void>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `page` | body | yes | JsonRequestPage<[UserVO](#model-uservo)> | 请求体 |

请求体字段：

[UserVO](#model-uservo)

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `createTime` | `Long` | 源自 `UserVO` |
| `updateTime` | `Long` | 源自 `UserVO` |
| `tenantId` | `String` | 源自 `UserVO` |
| `clientId` | `String` | 源自 `UserVO` |
| `id` | `Long` | 源自 `UserVO` |
| `username` | `String` | 源自 `UserVO` |
| `password` | `String` | 源自 `UserVO` |
| `openid` | `String` | 源自 `UserVO` |
| `weixinid` | `String` | 源自 `UserVO` |
| `weiboid` | `String` | 源自 `UserVO` |
| `githubid` | `String` | 源自 `UserVO` |
| `jiacn` | `String` | 源自 `UserVO` |
| `phone` | `String` | 源自 `UserVO` |
| `email` | `String` | 源自 `UserVO` |
| `sex` | `Integer` | 源自 `UserVO` |
| `nickname` | `String` | 源自 `UserVO` |
| `avatar` | `String` | 源自 `UserVO` |
| `city` | `String` | 源自 `UserVO` |
| `country` | `String` | 源自 `UserVO` |
| `province` | `String` | 源自 `UserVO` |
| `location` | `String` | 源自 `UserVO` |
| `latitude` | `String` | 源自 `UserVO` |
| `longitude` | `String` | 源自 `UserVO` |
| `point` | `Integer` | 源自 `UserVO` |
| `referrer` | `String` | 源自 `UserVO` |
| `birthday` | `LocalDate` | 源自 `UserVO` |
| `tel` | `String` | 源自 `UserVO` |
| `weixin` | `String` | 源自 `UserVO` |
| `qq` | `String` | 源自 `UserVO` |
| `position` | `String` | 源自 `UserVO` |
| `status` | `Integer` | 源自 `UserVO` |
| `remark` | `String` | 源自 `UserVO` |
| `msgType` | `String` | 源自 `UserVO` |
| `subscribe` | `String` | 源自 `UserVO` |
| `createTimeStart` | `Long` | 源自 `UserVO` |
| `createTimeEnd` | `Long` | 源自 `UserVO` |
| `updateTimeStart` | `Long` | 源自 `UserVO` |
| `updateTimeEnd` | `Long` | 源自 `UserVO` |
| `orgId` | `Long` | 源自 `UserVO` |
| `roleId` | `Long` | 源自 `UserVO` |
| `groupId` | `Long` | 源自 `UserVO` |
| `jiacnList` | `List<String>` | 源自 `UserVO` |
| `groupIds` | `List<Long>` | 源自 `UserVO` |
| `orgIds` | `List<Long>` | 源自 `UserVO` |
| `roleIds` | `List<Long>` | 源自 `UserVO` |
| `usernameLike` | `String` | 源自 `UserVO` |
| `openidLike` | `String` | 源自 `UserVO` |
| `jiacnLike` | `String` | 源自 `UserVO` |
| `phoneLike` | `String` | 源自 `UserVO` |
| `emailLike` | `String` | 源自 `UserVO` |
| `nicknameLike` | `String` | 源自 `UserVO` |
| `cityLike` | `String` | 源自 `UserVO` |
| `countryLike` | `String` | 源自 `UserVO` |
| `provinceLike` | `String` | 源自 `UserVO` |
| `referrerLike` | `String` | 源自 `UserVO` |

响应 data 说明：
- 成功时通常无 `data` 或 `data=null`。

#### POST `/org/create`

- 处理方法：`OrgController.create`
- 响应：[JsonResult](#model-jsonresult)<Void>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `org` | body | yes | [OrgEntity](#model-orgentity) | 请求体 |

请求体字段：

[OrgEntity](#model-orgentity)

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `createTime` | `Long` | 源自 `OrgEntity` |
| `updateTime` | `Long` | 源自 `OrgEntity` |
| `tenantId` | `String` | 源自 `OrgEntity` |
| `clientId` | `String` | 源自 `OrgEntity` |
| `id` | `Long` | 源自 `OrgEntity` |
| `name` | `String` | 源自 `OrgEntity` |
| `pId` | `Long` | 源自 `OrgEntity` |
| `type` | `Integer` | 源自 `OrgEntity` |
| `code` | `String` | 源自 `OrgEntity` |
| `remark` | `String` | 源自 `OrgEntity` |
| `director` | `String` | 源自 `OrgEntity` |
| `logo` | `String` | 源自 `OrgEntity` |
| `logoIcon` | `String` | 源自 `OrgEntity` |
| `status` | `Integer` | 源自 `OrgEntity` |

响应 data 说明：
- 成功时通常无 `data` 或 `data=null`。

#### POST `/org/update`

- 处理方法：`OrgController.update`
- 响应：[JsonResult](#model-jsonresult)<Void>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `org` | body | yes | [OrgEntity](#model-orgentity) | 请求体 |

请求体字段：

[OrgEntity](#model-orgentity)

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `createTime` | `Long` | 源自 `OrgEntity` |
| `updateTime` | `Long` | 源自 `OrgEntity` |
| `tenantId` | `String` | 源自 `OrgEntity` |
| `clientId` | `String` | 源自 `OrgEntity` |
| `id` | `Long` | 源自 `OrgEntity` |
| `name` | `String` | 源自 `OrgEntity` |
| `pId` | `Long` | 源自 `OrgEntity` |
| `type` | `Integer` | 源自 `OrgEntity` |
| `code` | `String` | 源自 `OrgEntity` |
| `remark` | `String` | 源自 `OrgEntity` |
| `director` | `String` | 源自 `OrgEntity` |
| `logo` | `String` | 源自 `OrgEntity` |
| `logoIcon` | `String` | 源自 `OrgEntity` |
| `status` | `Integer` | 源自 `OrgEntity` |

响应 data 说明：
- 成功时通常无 `data` 或 `data=null`。

#### DELETE `/org/delete`

- 处理方法：`OrgController.delete`
- 响应：[JsonResult](#model-jsonresult)<Void>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `id` | query | yes | Long | 查询/表单参数 |

响应 data 说明：
- 成功时通常无 `data` 或 `data=null`。

#### POST `/org/list`

- 处理方法：`OrgController.list`
- 响应：[JsonResult](#model-jsonresult)<Void>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `page` | body | yes | JsonRequestPage<[OrgEntity](#model-orgentity)> | 请求体 |

请求体字段：

[OrgEntity](#model-orgentity)

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `createTime` | `Long` | 源自 `OrgEntity` |
| `updateTime` | `Long` | 源自 `OrgEntity` |
| `tenantId` | `String` | 源自 `OrgEntity` |
| `clientId` | `String` | 源自 `OrgEntity` |
| `id` | `Long` | 源自 `OrgEntity` |
| `name` | `String` | 源自 `OrgEntity` |
| `pId` | `Long` | 源自 `OrgEntity` |
| `type` | `Integer` | 源自 `OrgEntity` |
| `code` | `String` | 源自 `OrgEntity` |
| `remark` | `String` | 源自 `OrgEntity` |
| `director` | `String` | 源自 `OrgEntity` |
| `logo` | `String` | 源自 `OrgEntity` |
| `logoIcon` | `String` | 源自 `OrgEntity` |
| `status` | `Integer` | 源自 `OrgEntity` |

响应 data 说明：
- 成功时通常无 `data` 或 `data=null`。

#### POST `/org/list/sub`

- 处理方法：`OrgController.listSub`
- 响应：[JsonResult](#model-jsonresult)<Void>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `page` | body | yes | JsonRequestPage<[OrgEntity](#model-orgentity)> | 请求体 |

请求体字段：

[OrgEntity](#model-orgentity)

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `createTime` | `Long` | 源自 `OrgEntity` |
| `updateTime` | `Long` | 源自 `OrgEntity` |
| `tenantId` | `String` | 源自 `OrgEntity` |
| `clientId` | `String` | 源自 `OrgEntity` |
| `id` | `Long` | 源自 `OrgEntity` |
| `name` | `String` | 源自 `OrgEntity` |
| `pId` | `Long` | 源自 `OrgEntity` |
| `type` | `Integer` | 源自 `OrgEntity` |
| `code` | `String` | 源自 `OrgEntity` |
| `remark` | `String` | 源自 `OrgEntity` |
| `director` | `String` | 源自 `OrgEntity` |
| `logo` | `String` | 源自 `OrgEntity` |
| `logoIcon` | `String` | 源自 `OrgEntity` |
| `status` | `Integer` | 源自 `OrgEntity` |

响应 data 说明：
- 成功时通常无 `data` 或 `data=null`。

#### POST `/org/users/add`

- 处理方法：`OrgController.userBatchAdd`
- 响应：[JsonResult](#model-jsonresult)<Void>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `org` | body | yes | [OrgVO](#model-orgvo) | 请求体 |

请求体字段：

[OrgVO](#model-orgvo)

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `createTime` | `Long` | 源自 `OrgVO` |
| `updateTime` | `Long` | 源自 `OrgVO` |
| `tenantId` | `String` | 源自 `OrgVO` |
| `clientId` | `String` | 源自 `OrgVO` |
| `id` | `Long` | 源自 `OrgVO` |
| `name` | `String` | 源自 `OrgVO` |
| `pId` | `Long` | 源自 `OrgVO` |
| `type` | `Integer` | 源自 `OrgVO` |
| `code` | `String` | 源自 `OrgVO` |
| `remark` | `String` | 源自 `OrgVO` |
| `director` | `String` | 源自 `OrgVO` |
| `logo` | `String` | 源自 `OrgVO` |
| `logoIcon` | `String` | 源自 `OrgVO` |
| `status` | `Integer` | 源自 `OrgVO` |
| `nameLike` | `String` | 源自 `OrgVO` |
| `codeLike` | `String` | 源自 `OrgVO` |
| `remarkLike` | `String` | 源自 `OrgVO` |
| `userIds` | `List<Long>` | 源自 `OrgVO` |
| `orgIds` | `List<Long>` | 源自 `OrgVO` |
| `directorNames` | `String` | 源自 `OrgVO` |

响应 data 说明：
- 成功时通常无 `data` 或 `data=null`。

#### POST `/org/users/del`

- 处理方法：`OrgController.userBatchDel`
- 响应：[JsonResult](#model-jsonresult)<Void>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `org` | body | yes | [OrgVO](#model-orgvo) | 请求体 |

请求体字段：

[OrgVO](#model-orgvo)

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `createTime` | `Long` | 源自 `OrgVO` |
| `updateTime` | `Long` | 源自 `OrgVO` |
| `tenantId` | `String` | 源自 `OrgVO` |
| `clientId` | `String` | 源自 `OrgVO` |
| `id` | `Long` | 源自 `OrgVO` |
| `name` | `String` | 源自 `OrgVO` |
| `pId` | `Long` | 源自 `OrgVO` |
| `type` | `Integer` | 源自 `OrgVO` |
| `code` | `String` | 源自 `OrgVO` |
| `remark` | `String` | 源自 `OrgVO` |
| `director` | `String` | 源自 `OrgVO` |
| `logo` | `String` | 源自 `OrgVO` |
| `logoIcon` | `String` | 源自 `OrgVO` |
| `status` | `Integer` | 源自 `OrgVO` |
| `nameLike` | `String` | 源自 `OrgVO` |
| `codeLike` | `String` | 源自 `OrgVO` |
| `remarkLike` | `String` | 源自 `OrgVO` |
| `userIds` | `List<Long>` | 源自 `OrgVO` |
| `orgIds` | `List<Long>` | 源自 `OrgVO` |
| `directorNames` | `String` | 源自 `OrgVO` |

响应 data 说明：
- 成功时通常无 `data` 或 `data=null`。

#### GET `/org/get/director`

- 处理方法：`OrgController.findDirector`
- 响应：[JsonResult](#model-jsonresult)<[UserVO](#model-uservo)>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `position` | query | yes | Long | 查询/表单参数 |
| `role` | query | yes | String | 查询/表单参数 |

响应 data 说明：
- 按 `JsonResult<UserVO>` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

#### POST `/org/update/logo`

- 处理方法：`OrgController.updateLogo`
- 响应：[JsonResult](#model-jsonresult)<[OrgEntity](#model-orgentity)>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `id` | query | yes | Long | 查询/表单参数 |
| `type` | query | yes | Long | 查询/表单参数 |
| `file` | multipart | yes | MultipartFile | 文件/表单参数 |

请求体字段：

响应 data 说明：
- 按 `JsonResult<OrgEntity>` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

### PermsController `/action`

源码：`user/jia-user-service/src/main/java/cn/jia/user/api/PermsController.java`

#### GET `/action/get`

- 处理方法：`PermsController.findById`
- 响应：[JsonResult](#model-jsonresult)<[PermsEntity](#model-permsentity)>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `id` | query | yes | Integer | 查询/表单参数 |

响应 data 说明：
- 按 `JsonResult<PermsEntity>` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

#### POST `/action/create`

- 处理方法：`PermsController.create`
- 响应：[JsonResult](#model-jsonresult)<Void>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `action` | body | yes | [PermsEntity](#model-permsentity) | 请求体 |

请求体字段：

[PermsEntity](#model-permsentity)

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `createTime` | `Long` | 源自 `PermsEntity` |
| `updateTime` | `Long` | 源自 `PermsEntity` |
| `tenantId` | `String` | 源自 `PermsEntity` |
| `clientId` | `String` | 源自 `PermsEntity` |
| `id` | `Long` | 源自 `PermsEntity` |
| `module` | `String` | 源自 `PermsEntity` |
| `func` | `String` | 源自 `PermsEntity` |
| `url` | `String` | 源自 `PermsEntity` |
| `description` | `String` | 源自 `PermsEntity` |
| `source` | `Integer` | 源自 `PermsEntity` |
| `level` | `Integer` | 源自 `PermsEntity` |
| `status` | `Integer` | 源自 `PermsEntity` |

响应 data 说明：
- 成功时通常无 `data` 或 `data=null`。

#### POST `/action/update`

- 处理方法：`PermsController.update`
- 响应：[JsonResult](#model-jsonresult)<Void>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `action` | body | yes | [PermsEntity](#model-permsentity) | 请求体 |

请求体字段：

[PermsEntity](#model-permsentity)

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `createTime` | `Long` | 源自 `PermsEntity` |
| `updateTime` | `Long` | 源自 `PermsEntity` |
| `tenantId` | `String` | 源自 `PermsEntity` |
| `clientId` | `String` | 源自 `PermsEntity` |
| `id` | `Long` | 源自 `PermsEntity` |
| `module` | `String` | 源自 `PermsEntity` |
| `func` | `String` | 源自 `PermsEntity` |
| `url` | `String` | 源自 `PermsEntity` |
| `description` | `String` | 源自 `PermsEntity` |
| `source` | `Integer` | 源自 `PermsEntity` |
| `level` | `Integer` | 源自 `PermsEntity` |
| `status` | `Integer` | 源自 `PermsEntity` |

响应 data 说明：
- 成功时通常无 `data` 或 `data=null`。

#### POST `/action/refresh`

- 处理方法：`PermsController.refresh`
- 响应：[JsonResult](#model-jsonresult)<Void>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `actionList` | body | yes | List<[PermsEntity](#model-permsentity)> | 请求体 |

请求体字段：

[PermsEntity](#model-permsentity)

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `createTime` | `Long` | 源自 `PermsEntity` |
| `updateTime` | `Long` | 源自 `PermsEntity` |
| `tenantId` | `String` | 源自 `PermsEntity` |
| `clientId` | `String` | 源自 `PermsEntity` |
| `id` | `Long` | 源自 `PermsEntity` |
| `module` | `String` | 源自 `PermsEntity` |
| `func` | `String` | 源自 `PermsEntity` |
| `url` | `String` | 源自 `PermsEntity` |
| `description` | `String` | 源自 `PermsEntity` |
| `source` | `Integer` | 源自 `PermsEntity` |
| `level` | `Integer` | 源自 `PermsEntity` |
| `status` | `Integer` | 源自 `PermsEntity` |

响应 data 说明：
- 成功时通常无 `data` 或 `data=null`。

#### DELETE `/action/delete`

- 处理方法：`PermsController.delete`
- 响应：[JsonResult](#model-jsonresult)<Void>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `id` | query | yes | Integer | 查询/表单参数 |

响应 data 说明：
- 成功时通常无 `data` 或 `data=null`。

#### POST `/action/list`

- 处理方法：`PermsController.list`
- 响应：[JsonResult](#model-jsonresult)<Void>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `page` | body | yes | JsonRequestPage<[PermsEntity](#model-permsentity)> | 请求体 |

请求体字段：

[PermsEntity](#model-permsentity)

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `createTime` | `Long` | 源自 `PermsEntity` |
| `updateTime` | `Long` | 源自 `PermsEntity` |
| `tenantId` | `String` | 源自 `PermsEntity` |
| `clientId` | `String` | 源自 `PermsEntity` |
| `id` | `Long` | 源自 `PermsEntity` |
| `module` | `String` | 源自 `PermsEntity` |
| `func` | `String` | 源自 `PermsEntity` |
| `url` | `String` | 源自 `PermsEntity` |
| `description` | `String` | 源自 `PermsEntity` |
| `source` | `Integer` | 源自 `PermsEntity` |
| `level` | `Integer` | 源自 `PermsEntity` |
| `status` | `Integer` | 源自 `PermsEntity` |

响应 data 说明：
- 成功时通常无 `data` 或 `data=null`。

### RoleController `/role`

源码：`user/jia-user-service/src/main/java/cn/jia/user/api/RoleController.java`

#### GET `/role/get`

- 处理方法：`RoleController.findById`
- 响应：[JsonResult](#model-jsonresult)<[RoleEntity](#model-roleentity)>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `id` | query | yes | Long | 查询/表单参数 |

响应 data 说明：
- 按 `JsonResult<RoleEntity>` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

#### POST `/role/get/users`

- 处理方法：`RoleController.findUsers`
- 响应：[JsonResult](#model-jsonresult)<Void>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `page` | body | yes | JsonRequestPage<[RoleEntity](#model-roleentity)> | 请求体 |

请求体字段：

[RoleEntity](#model-roleentity)

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `createTime` | `Long` | 源自 `RoleEntity` |
| `updateTime` | `Long` | 源自 `RoleEntity` |
| `tenantId` | `String` | 源自 `RoleEntity` |
| `clientId` | `String` | 源自 `RoleEntity` |
| `id` | `Long` | 源自 `RoleEntity` |
| `name` | `String` | 源自 `RoleEntity` |
| `code` | `String` | 源自 `RoleEntity` |
| `remark` | `String` | 源自 `RoleEntity` |
| `status` | `Integer` | 源自 `RoleEntity` |

响应 data 说明：
- 成功时通常无 `data` 或 `data=null`。

#### POST `/role/get/perms`

- 处理方法：`RoleController.findPerms`
- 响应：[JsonResult](#model-jsonresult)<Void>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `page` | body | yes | JsonRequestPage<[RoleEntity](#model-roleentity)> | 请求体 |

请求体字段：

[RoleEntity](#model-roleentity)

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `createTime` | `Long` | 源自 `RoleEntity` |
| `updateTime` | `Long` | 源自 `RoleEntity` |
| `tenantId` | `String` | 源自 `RoleEntity` |
| `clientId` | `String` | 源自 `RoleEntity` |
| `id` | `Long` | 源自 `RoleEntity` |
| `name` | `String` | 源自 `RoleEntity` |
| `code` | `String` | 源自 `RoleEntity` |
| `remark` | `String` | 源自 `RoleEntity` |
| `status` | `Integer` | 源自 `RoleEntity` |

响应 data 说明：
- 成功时通常无 `data` 或 `data=null`。

#### POST `/role/create`

- 处理方法：`RoleController.create`
- 响应：[JsonResult](#model-jsonresult)<Void>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `role` | body | yes | [RoleEntity](#model-roleentity) | 请求体 |

请求体字段：

[RoleEntity](#model-roleentity)

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `createTime` | `Long` | 源自 `RoleEntity` |
| `updateTime` | `Long` | 源自 `RoleEntity` |
| `tenantId` | `String` | 源自 `RoleEntity` |
| `clientId` | `String` | 源自 `RoleEntity` |
| `id` | `Long` | 源自 `RoleEntity` |
| `name` | `String` | 源自 `RoleEntity` |
| `code` | `String` | 源自 `RoleEntity` |
| `remark` | `String` | 源自 `RoleEntity` |
| `status` | `Integer` | 源自 `RoleEntity` |

响应 data 说明：
- 成功时通常无 `data` 或 `data=null`。

#### POST `/role/update`

- 处理方法：`RoleController.update`
- 响应：[JsonResult](#model-jsonresult)<Void>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `role` | body | yes | [RoleEntity](#model-roleentity) | 请求体 |

请求体字段：

[RoleEntity](#model-roleentity)

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `createTime` | `Long` | 源自 `RoleEntity` |
| `updateTime` | `Long` | 源自 `RoleEntity` |
| `tenantId` | `String` | 源自 `RoleEntity` |
| `clientId` | `String` | 源自 `RoleEntity` |
| `id` | `Long` | 源自 `RoleEntity` |
| `name` | `String` | 源自 `RoleEntity` |
| `code` | `String` | 源自 `RoleEntity` |
| `remark` | `String` | 源自 `RoleEntity` |
| `status` | `Integer` | 源自 `RoleEntity` |

响应 data 说明：
- 成功时通常无 `data` 或 `data=null`。

#### DELETE `/role/delete`

- 处理方法：`RoleController.delete`
- 响应：[JsonResult](#model-jsonresult)<Void>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `id` | query | yes | Long | 查询/表单参数 |

响应 data 说明：
- 成功时通常无 `data` 或 `data=null`。

#### POST `/role/list`

- 处理方法：`RoleController.list`
- 响应：[JsonResult](#model-jsonresult)<Void>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `page` | body | yes | JsonRequestPage<[RoleEntity](#model-roleentity)> | 请求体 |

请求体字段：

[RoleEntity](#model-roleentity)

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `createTime` | `Long` | 源自 `RoleEntity` |
| `updateTime` | `Long` | 源自 `RoleEntity` |
| `tenantId` | `String` | 源自 `RoleEntity` |
| `clientId` | `String` | 源自 `RoleEntity` |
| `id` | `Long` | 源自 `RoleEntity` |
| `name` | `String` | 源自 `RoleEntity` |
| `code` | `String` | 源自 `RoleEntity` |
| `remark` | `String` | 源自 `RoleEntity` |
| `status` | `Integer` | 源自 `RoleEntity` |

响应 data 说明：
- 成功时通常无 `data` 或 `data=null`。

#### POST `/role/perms/change`

- 处理方法：`RoleController.changePerms`
- 响应：[JsonResult](#model-jsonresult)<Void>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `role` | body | yes | [RoleVO](#model-rolevo) | 请求体 |

请求体字段：

[RoleVO](#model-rolevo)

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `createTime` | `Long` | 源自 `RoleVO` |
| `updateTime` | `Long` | 源自 `RoleVO` |
| `tenantId` | `String` | 源自 `RoleVO` |
| `clientId` | `String` | 源自 `RoleVO` |
| `id` | `Long` | 源自 `RoleVO` |
| `name` | `String` | 源自 `RoleVO` |
| `code` | `String` | 源自 `RoleVO` |
| `remark` | `String` | 源自 `RoleVO` |
| `status` | `Integer` | 源自 `RoleVO` |
| `nameLike` | `String` | 源自 `RoleVO` |
| `codeLike` | `String` | 源自 `RoleVO` |
| `remarkLike` | `String` | 源自 `RoleVO` |
| `userIds` | `List<Long>` | 源自 `RoleVO` |
| `permsIds` | `List<Long>` | 源自 `RoleVO` |
| `groupIds` | `List<Long>` | 源自 `RoleVO` |

响应 data 说明：
- 成功时通常无 `data` 或 `data=null`。

#### POST `/role/users/add`

- 处理方法：`RoleController.userBatchAdd`
- 响应：[JsonResult](#model-jsonresult)<Void>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `role` | body | yes | [RoleVO](#model-rolevo) | 请求体 |

请求体字段：

[RoleVO](#model-rolevo)

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `createTime` | `Long` | 源自 `RoleVO` |
| `updateTime` | `Long` | 源自 `RoleVO` |
| `tenantId` | `String` | 源自 `RoleVO` |
| `clientId` | `String` | 源自 `RoleVO` |
| `id` | `Long` | 源自 `RoleVO` |
| `name` | `String` | 源自 `RoleVO` |
| `code` | `String` | 源自 `RoleVO` |
| `remark` | `String` | 源自 `RoleVO` |
| `status` | `Integer` | 源自 `RoleVO` |
| `nameLike` | `String` | 源自 `RoleVO` |
| `codeLike` | `String` | 源自 `RoleVO` |
| `remarkLike` | `String` | 源自 `RoleVO` |
| `userIds` | `List<Long>` | 源自 `RoleVO` |
| `permsIds` | `List<Long>` | 源自 `RoleVO` |
| `groupIds` | `List<Long>` | 源自 `RoleVO` |

响应 data 说明：
- 成功时通常无 `data` 或 `data=null`。

#### POST `/role/users/del`

- 处理方法：`RoleController.userBatchDel`
- 响应：[JsonResult](#model-jsonresult)<Void>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `role` | body | yes | [RoleVO](#model-rolevo) | 请求体 |

请求体字段：

[RoleVO](#model-rolevo)

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `createTime` | `Long` | 源自 `RoleVO` |
| `updateTime` | `Long` | 源自 `RoleVO` |
| `tenantId` | `String` | 源自 `RoleVO` |
| `clientId` | `String` | 源自 `RoleVO` |
| `id` | `Long` | 源自 `RoleVO` |
| `name` | `String` | 源自 `RoleVO` |
| `code` | `String` | 源自 `RoleVO` |
| `remark` | `String` | 源自 `RoleVO` |
| `status` | `Integer` | 源自 `RoleVO` |
| `nameLike` | `String` | 源自 `RoleVO` |
| `codeLike` | `String` | 源自 `RoleVO` |
| `remarkLike` | `String` | 源自 `RoleVO` |
| `userIds` | `List<Long>` | 源自 `RoleVO` |
| `permsIds` | `List<Long>` | 源自 `RoleVO` |
| `groupIds` | `List<Long>` | 源自 `RoleVO` |

响应 data 说明：
- 成功时通常无 `data` 或 `data=null`。

### UserController `/user`

源码：`user/jia-user-service/src/main/java/cn/jia/user/api/UserController.java`

#### GET `/user/get`

- 处理方法：`UserController.find`
- 响应：[JsonResult](#model-jsonresult)<[UserVO](#model-uservo)>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `type` | query | yes | String | 查询/表单参数 |
| `key` | query | yes | String | 查询/表单参数 |

响应 data 说明：
- 按 `JsonResult<UserVO>` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

#### GET `/user/get/name`

- 处理方法：`UserController.findNameById`
- 响应：String

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `id` | query | yes | Long | 查询/表单参数 |

响应 data 说明：
- 按 `String` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

#### POST `/user/get/roles`

- 处理方法：`UserController.findRoles`
- 响应：[JsonResult](#model-jsonresult)<Void>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `page` | body | yes | JsonRequestPage<[UserEntity](#model-userentity)> | 请求体 |

请求体字段：

[UserEntity](#model-userentity)

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `createTime` | `Long` | 源自 `UserEntity` |
| `updateTime` | `Long` | 源自 `UserEntity` |
| `tenantId` | `String` | 源自 `UserEntity` |
| `clientId` | `String` | 源自 `UserEntity` |
| `id` | `Long` | 源自 `UserEntity` |
| `username` | `String` | 源自 `UserEntity` |
| `password` | `String` | 源自 `UserEntity` |
| `openid` | `String` | 源自 `UserEntity` |
| `weixinid` | `String` | 源自 `UserEntity` |
| `weiboid` | `String` | 源自 `UserEntity` |
| `githubid` | `String` | 源自 `UserEntity` |
| `jiacn` | `String` | 源自 `UserEntity` |
| `phone` | `String` | 源自 `UserEntity` |
| `email` | `String` | 源自 `UserEntity` |
| `sex` | `Integer` | 源自 `UserEntity` |
| `nickname` | `String` | 源自 `UserEntity` |
| `avatar` | `String` | 源自 `UserEntity` |
| `city` | `String` | 源自 `UserEntity` |
| `country` | `String` | 源自 `UserEntity` |
| `province` | `String` | 源自 `UserEntity` |
| `location` | `String` | 源自 `UserEntity` |
| `latitude` | `String` | 源自 `UserEntity` |
| `longitude` | `String` | 源自 `UserEntity` |
| `point` | `Integer` | 源自 `UserEntity` |
| `referrer` | `String` | 源自 `UserEntity` |
| `birthday` | `LocalDate` | 源自 `UserEntity` |
| `tel` | `String` | 源自 `UserEntity` |
| `weixin` | `String` | 源自 `UserEntity` |
| `qq` | `String` | 源自 `UserEntity` |
| `position` | `String` | 源自 `UserEntity` |
| `status` | `Integer` | 源自 `UserEntity` |
| `remark` | `String` | 源自 `UserEntity` |
| `msgType` | `String` | 源自 `UserEntity` |
| `subscribe` | `String` | 源自 `UserEntity` |

响应 data 说明：
- 成功时通常无 `data` 或 `data=null`。

#### GET `/user/get/orgs`

- 处理方法：`UserController.findOrgs`
- 响应：[JsonResult](#model-jsonresult)<List<[OrgEntity](#model-orgentity)>>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `id` | query | yes | Long | 查询/表单参数 |

响应 data 说明：
- 按 `JsonResult<List<OrgEntity>>` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

#### GET `/user/check`

- 处理方法：`UserController.check`
- 响应：[JsonResult](#model-jsonresult)<expression: user == null ? 0 : 1>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `type` | query | yes | String | 查询/表单参数 |
| `key` | query | yes | String | 查询/表单参数 |

响应 data 说明：
- 按 `JsonResult<expression: user == null ? 0 : 1>` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

#### POST `/user/create`

- 处理方法：`UserController.create`
- 响应：[JsonResult](#model-jsonresult)<[UserEntity](#model-userentity)>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `user` | body | yes | [UserEntity](#model-userentity) | 请求体 |

请求体字段：

[UserEntity](#model-userentity)

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `createTime` | `Long` | 源自 `UserEntity` |
| `updateTime` | `Long` | 源自 `UserEntity` |
| `tenantId` | `String` | 源自 `UserEntity` |
| `clientId` | `String` | 源自 `UserEntity` |
| `id` | `Long` | 源自 `UserEntity` |
| `username` | `String` | 源自 `UserEntity` |
| `password` | `String` | 源自 `UserEntity` |
| `openid` | `String` | 源自 `UserEntity` |
| `weixinid` | `String` | 源自 `UserEntity` |
| `weiboid` | `String` | 源自 `UserEntity` |
| `githubid` | `String` | 源自 `UserEntity` |
| `jiacn` | `String` | 源自 `UserEntity` |
| `phone` | `String` | 源自 `UserEntity` |
| `email` | `String` | 源自 `UserEntity` |
| `sex` | `Integer` | 源自 `UserEntity` |
| `nickname` | `String` | 源自 `UserEntity` |
| `avatar` | `String` | 源自 `UserEntity` |
| `city` | `String` | 源自 `UserEntity` |
| `country` | `String` | 源自 `UserEntity` |
| `province` | `String` | 源自 `UserEntity` |
| `location` | `String` | 源自 `UserEntity` |
| `latitude` | `String` | 源自 `UserEntity` |
| `longitude` | `String` | 源自 `UserEntity` |
| `point` | `Integer` | 源自 `UserEntity` |
| `referrer` | `String` | 源自 `UserEntity` |
| `birthday` | `LocalDate` | 源自 `UserEntity` |
| `tel` | `String` | 源自 `UserEntity` |
| `weixin` | `String` | 源自 `UserEntity` |
| `qq` | `String` | 源自 `UserEntity` |
| `position` | `String` | 源自 `UserEntity` |
| `status` | `Integer` | 源自 `UserEntity` |
| `remark` | `String` | 源自 `UserEntity` |
| `msgType` | `String` | 源自 `UserEntity` |
| `subscribe` | `String` | 源自 `UserEntity` |

响应 data 说明：
- 按 `JsonResult<UserEntity>` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

#### POST `/user/update`

- 处理方法：`UserController.update`
- 响应：[JsonResult](#model-jsonresult)<[UserEntity](#model-userentity)>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `user` | body | yes | [UserEntity](#model-userentity) | 请求体 |

请求体字段：

[UserEntity](#model-userentity)

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `createTime` | `Long` | 源自 `UserEntity` |
| `updateTime` | `Long` | 源自 `UserEntity` |
| `tenantId` | `String` | 源自 `UserEntity` |
| `clientId` | `String` | 源自 `UserEntity` |
| `id` | `Long` | 源自 `UserEntity` |
| `username` | `String` | 源自 `UserEntity` |
| `password` | `String` | 源自 `UserEntity` |
| `openid` | `String` | 源自 `UserEntity` |
| `weixinid` | `String` | 源自 `UserEntity` |
| `weiboid` | `String` | 源自 `UserEntity` |
| `githubid` | `String` | 源自 `UserEntity` |
| `jiacn` | `String` | 源自 `UserEntity` |
| `phone` | `String` | 源自 `UserEntity` |
| `email` | `String` | 源自 `UserEntity` |
| `sex` | `Integer` | 源自 `UserEntity` |
| `nickname` | `String` | 源自 `UserEntity` |
| `avatar` | `String` | 源自 `UserEntity` |
| `city` | `String` | 源自 `UserEntity` |
| `country` | `String` | 源自 `UserEntity` |
| `province` | `String` | 源自 `UserEntity` |
| `location` | `String` | 源自 `UserEntity` |
| `latitude` | `String` | 源自 `UserEntity` |
| `longitude` | `String` | 源自 `UserEntity` |
| `point` | `Integer` | 源自 `UserEntity` |
| `referrer` | `String` | 源自 `UserEntity` |
| `birthday` | `LocalDate` | 源自 `UserEntity` |
| `tel` | `String` | 源自 `UserEntity` |
| `weixin` | `String` | 源自 `UserEntity` |
| `qq` | `String` | 源自 `UserEntity` |
| `position` | `String` | 源自 `UserEntity` |
| `status` | `Integer` | 源自 `UserEntity` |
| `remark` | `String` | 源自 `UserEntity` |
| `msgType` | `String` | 源自 `UserEntity` |
| `subscribe` | `String` | 源自 `UserEntity` |

响应 data 说明：
- 按 `JsonResult<UserEntity>` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

#### POST `/user/sync`

- 处理方法：`UserController.sync`
- 响应：[JsonResult](#model-jsonresult)<Void>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `userList` | body | yes | List<[UserEntity](#model-userentity)> | 请求体 |

请求体字段：

[UserEntity](#model-userentity)

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `createTime` | `Long` | 源自 `UserEntity` |
| `updateTime` | `Long` | 源自 `UserEntity` |
| `tenantId` | `String` | 源自 `UserEntity` |
| `clientId` | `String` | 源自 `UserEntity` |
| `id` | `Long` | 源自 `UserEntity` |
| `username` | `String` | 源自 `UserEntity` |
| `password` | `String` | 源自 `UserEntity` |
| `openid` | `String` | 源自 `UserEntity` |
| `weixinid` | `String` | 源自 `UserEntity` |
| `weiboid` | `String` | 源自 `UserEntity` |
| `githubid` | `String` | 源自 `UserEntity` |
| `jiacn` | `String` | 源自 `UserEntity` |
| `phone` | `String` | 源自 `UserEntity` |
| `email` | `String` | 源自 `UserEntity` |
| `sex` | `Integer` | 源自 `UserEntity` |
| `nickname` | `String` | 源自 `UserEntity` |
| `avatar` | `String` | 源自 `UserEntity` |
| `city` | `String` | 源自 `UserEntity` |
| `country` | `String` | 源自 `UserEntity` |
| `province` | `String` | 源自 `UserEntity` |
| `location` | `String` | 源自 `UserEntity` |
| `latitude` | `String` | 源自 `UserEntity` |
| `longitude` | `String` | 源自 `UserEntity` |
| `point` | `Integer` | 源自 `UserEntity` |
| `referrer` | `String` | 源自 `UserEntity` |
| `birthday` | `LocalDate` | 源自 `UserEntity` |
| `tel` | `String` | 源自 `UserEntity` |
| `weixin` | `String` | 源自 `UserEntity` |
| `qq` | `String` | 源自 `UserEntity` |
| `position` | `String` | 源自 `UserEntity` |
| `status` | `Integer` | 源自 `UserEntity` |
| `remark` | `String` | 源自 `UserEntity` |
| `msgType` | `String` | 源自 `UserEntity` |
| `subscribe` | `String` | 源自 `UserEntity` |

响应 data 说明：
- 成功时通常无 `data` 或 `data=null`。

#### DELETE `/user/delete`

- 处理方法：`UserController.delete`
- 响应：[JsonResult](#model-jsonresult)<Void>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `id` | query | yes | Long | 查询/表单参数 |

响应 data 说明：
- 成功时通常无 `data` 或 `data=null`。

#### POST `/user/list`

- 处理方法：`UserController.list`
- 响应：[JsonResult](#model-jsonresult)<Void>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `page` | body | yes | JsonRequestPage<[UserVO](#model-uservo)> | 请求体 |

请求体字段：

[UserVO](#model-uservo)

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `createTime` | `Long` | 源自 `UserVO` |
| `updateTime` | `Long` | 源自 `UserVO` |
| `tenantId` | `String` | 源自 `UserVO` |
| `clientId` | `String` | 源自 `UserVO` |
| `id` | `Long` | 源自 `UserVO` |
| `username` | `String` | 源自 `UserVO` |
| `password` | `String` | 源自 `UserVO` |
| `openid` | `String` | 源自 `UserVO` |
| `weixinid` | `String` | 源自 `UserVO` |
| `weiboid` | `String` | 源自 `UserVO` |
| `githubid` | `String` | 源自 `UserVO` |
| `jiacn` | `String` | 源自 `UserVO` |
| `phone` | `String` | 源自 `UserVO` |
| `email` | `String` | 源自 `UserVO` |
| `sex` | `Integer` | 源自 `UserVO` |
| `nickname` | `String` | 源自 `UserVO` |
| `avatar` | `String` | 源自 `UserVO` |
| `city` | `String` | 源自 `UserVO` |
| `country` | `String` | 源自 `UserVO` |
| `province` | `String` | 源自 `UserVO` |
| `location` | `String` | 源自 `UserVO` |
| `latitude` | `String` | 源自 `UserVO` |
| `longitude` | `String` | 源自 `UserVO` |
| `point` | `Integer` | 源自 `UserVO` |
| `referrer` | `String` | 源自 `UserVO` |
| `birthday` | `LocalDate` | 源自 `UserVO` |
| `tel` | `String` | 源自 `UserVO` |
| `weixin` | `String` | 源自 `UserVO` |
| `qq` | `String` | 源自 `UserVO` |
| `position` | `String` | 源自 `UserVO` |
| `status` | `Integer` | 源自 `UserVO` |
| `remark` | `String` | 源自 `UserVO` |
| `msgType` | `String` | 源自 `UserVO` |
| `subscribe` | `String` | 源自 `UserVO` |
| `createTimeStart` | `Long` | 源自 `UserVO` |
| `createTimeEnd` | `Long` | 源自 `UserVO` |
| `updateTimeStart` | `Long` | 源自 `UserVO` |
| `updateTimeEnd` | `Long` | 源自 `UserVO` |
| `orgId` | `Long` | 源自 `UserVO` |
| `roleId` | `Long` | 源自 `UserVO` |
| `groupId` | `Long` | 源自 `UserVO` |
| `jiacnList` | `List<String>` | 源自 `UserVO` |
| `groupIds` | `List<Long>` | 源自 `UserVO` |
| `orgIds` | `List<Long>` | 源自 `UserVO` |
| `roleIds` | `List<Long>` | 源自 `UserVO` |
| `usernameLike` | `String` | 源自 `UserVO` |
| `openidLike` | `String` | 源自 `UserVO` |
| `jiacnLike` | `String` | 源自 `UserVO` |
| `phoneLike` | `String` | 源自 `UserVO` |
| `emailLike` | `String` | 源自 `UserVO` |
| `nicknameLike` | `String` | 源自 `UserVO` |
| `cityLike` | `String` | 源自 `UserVO` |
| `countryLike` | `String` | 源自 `UserVO` |
| `provinceLike` | `String` | 源自 `UserVO` |
| `referrerLike` | `String` | 源自 `UserVO` |

响应 data 说明：
- 成功时通常无 `data` 或 `data=null`。

#### POST `/user/search`

- 处理方法：`UserController.search`
- 响应：[JsonResult](#model-jsonresult)<Void>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `page` | body | yes | JsonRequestPage<[UserEntity](#model-userentity)> | 请求体 |

请求体字段：

[UserEntity](#model-userentity)

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `createTime` | `Long` | 源自 `UserEntity` |
| `updateTime` | `Long` | 源自 `UserEntity` |
| `tenantId` | `String` | 源自 `UserEntity` |
| `clientId` | `String` | 源自 `UserEntity` |
| `id` | `Long` | 源自 `UserEntity` |
| `username` | `String` | 源自 `UserEntity` |
| `password` | `String` | 源自 `UserEntity` |
| `openid` | `String` | 源自 `UserEntity` |
| `weixinid` | `String` | 源自 `UserEntity` |
| `weiboid` | `String` | 源自 `UserEntity` |
| `githubid` | `String` | 源自 `UserEntity` |
| `jiacn` | `String` | 源自 `UserEntity` |
| `phone` | `String` | 源自 `UserEntity` |
| `email` | `String` | 源自 `UserEntity` |
| `sex` | `Integer` | 源自 `UserEntity` |
| `nickname` | `String` | 源自 `UserEntity` |
| `avatar` | `String` | 源自 `UserEntity` |
| `city` | `String` | 源自 `UserEntity` |
| `country` | `String` | 源自 `UserEntity` |
| `province` | `String` | 源自 `UserEntity` |
| `location` | `String` | 源自 `UserEntity` |
| `latitude` | `String` | 源自 `UserEntity` |
| `longitude` | `String` | 源自 `UserEntity` |
| `point` | `Integer` | 源自 `UserEntity` |
| `referrer` | `String` | 源自 `UserEntity` |
| `birthday` | `LocalDate` | 源自 `UserEntity` |
| `tel` | `String` | 源自 `UserEntity` |
| `weixin` | `String` | 源自 `UserEntity` |
| `qq` | `String` | 源自 `UserEntity` |
| `position` | `String` | 源自 `UserEntity` |
| `status` | `Integer` | 源自 `UserEntity` |
| `remark` | `String` | 源自 `UserEntity` |
| `msgType` | `String` | 源自 `UserEntity` |
| `subscribe` | `String` | 源自 `UserEntity` |

响应 data 说明：
- 成功时通常无 `data` 或 `data=null`。

#### GET `/user/point/change`

- 处理方法：`UserController.changePoint`
- 响应：[JsonResult](#model-jsonresult)<Void>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `jiacn` | query | yes | String | 查询/表单参数 |
| `num` | query | yes | int | 查询/表单参数 |

响应 data 说明：
- 成功时通常无 `data` 或 `data=null`。

#### POST `/user/role/change`

- 处理方法：`UserController.changeRole`
- 响应：[JsonResult](#model-jsonresult)<Void>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `user` | body | yes | [UserVO](#model-uservo) | 请求体 |

请求体字段：

[UserVO](#model-uservo)

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `createTime` | `Long` | 源自 `UserVO` |
| `updateTime` | `Long` | 源自 `UserVO` |
| `tenantId` | `String` | 源自 `UserVO` |
| `clientId` | `String` | 源自 `UserVO` |
| `id` | `Long` | 源自 `UserVO` |
| `username` | `String` | 源自 `UserVO` |
| `password` | `String` | 源自 `UserVO` |
| `openid` | `String` | 源自 `UserVO` |
| `weixinid` | `String` | 源自 `UserVO` |
| `weiboid` | `String` | 源自 `UserVO` |
| `githubid` | `String` | 源自 `UserVO` |
| `jiacn` | `String` | 源自 `UserVO` |
| `phone` | `String` | 源自 `UserVO` |
| `email` | `String` | 源自 `UserVO` |
| `sex` | `Integer` | 源自 `UserVO` |
| `nickname` | `String` | 源自 `UserVO` |
| `avatar` | `String` | 源自 `UserVO` |
| `city` | `String` | 源自 `UserVO` |
| `country` | `String` | 源自 `UserVO` |
| `province` | `String` | 源自 `UserVO` |
| `location` | `String` | 源自 `UserVO` |
| `latitude` | `String` | 源自 `UserVO` |
| `longitude` | `String` | 源自 `UserVO` |
| `point` | `Integer` | 源自 `UserVO` |
| `referrer` | `String` | 源自 `UserVO` |
| `birthday` | `LocalDate` | 源自 `UserVO` |
| `tel` | `String` | 源自 `UserVO` |
| `weixin` | `String` | 源自 `UserVO` |
| `qq` | `String` | 源自 `UserVO` |
| `position` | `String` | 源自 `UserVO` |
| `status` | `Integer` | 源自 `UserVO` |
| `remark` | `String` | 源自 `UserVO` |
| `msgType` | `String` | 源自 `UserVO` |
| `subscribe` | `String` | 源自 `UserVO` |
| `createTimeStart` | `Long` | 源自 `UserVO` |
| `createTimeEnd` | `Long` | 源自 `UserVO` |
| `updateTimeStart` | `Long` | 源自 `UserVO` |
| `updateTimeEnd` | `Long` | 源自 `UserVO` |
| `orgId` | `Long` | 源自 `UserVO` |
| `roleId` | `Long` | 源自 `UserVO` |
| `groupId` | `Long` | 源自 `UserVO` |
| `jiacnList` | `List<String>` | 源自 `UserVO` |
| `groupIds` | `List<Long>` | 源自 `UserVO` |
| `orgIds` | `List<Long>` | 源自 `UserVO` |
| `roleIds` | `List<Long>` | 源自 `UserVO` |
| `usernameLike` | `String` | 源自 `UserVO` |
| `openidLike` | `String` | 源自 `UserVO` |
| `jiacnLike` | `String` | 源自 `UserVO` |
| `phoneLike` | `String` | 源自 `UserVO` |
| `emailLike` | `String` | 源自 `UserVO` |
| `nicknameLike` | `String` | 源自 `UserVO` |
| `cityLike` | `String` | 源自 `UserVO` |
| `countryLike` | `String` | 源自 `UserVO` |
| `provinceLike` | `String` | 源自 `UserVO` |
| `referrerLike` | `String` | 源自 `UserVO` |

响应 data 说明：
- 成功时通常无 `data` 或 `data=null`。

#### POST `/user/group/change`

- 处理方法：`UserController.changeGroup`
- 响应：[JsonResult](#model-jsonresult)<Void>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `user` | body | yes | [UserVO](#model-uservo) | 请求体 |

请求体字段：

[UserVO](#model-uservo)

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `createTime` | `Long` | 源自 `UserVO` |
| `updateTime` | `Long` | 源自 `UserVO` |
| `tenantId` | `String` | 源自 `UserVO` |
| `clientId` | `String` | 源自 `UserVO` |
| `id` | `Long` | 源自 `UserVO` |
| `username` | `String` | 源自 `UserVO` |
| `password` | `String` | 源自 `UserVO` |
| `openid` | `String` | 源自 `UserVO` |
| `weixinid` | `String` | 源自 `UserVO` |
| `weiboid` | `String` | 源自 `UserVO` |
| `githubid` | `String` | 源自 `UserVO` |
| `jiacn` | `String` | 源自 `UserVO` |
| `phone` | `String` | 源自 `UserVO` |
| `email` | `String` | 源自 `UserVO` |
| `sex` | `Integer` | 源自 `UserVO` |
| `nickname` | `String` | 源自 `UserVO` |
| `avatar` | `String` | 源自 `UserVO` |
| `city` | `String` | 源自 `UserVO` |
| `country` | `String` | 源自 `UserVO` |
| `province` | `String` | 源自 `UserVO` |
| `location` | `String` | 源自 `UserVO` |
| `latitude` | `String` | 源自 `UserVO` |
| `longitude` | `String` | 源自 `UserVO` |
| `point` | `Integer` | 源自 `UserVO` |
| `referrer` | `String` | 源自 `UserVO` |
| `birthday` | `LocalDate` | 源自 `UserVO` |
| `tel` | `String` | 源自 `UserVO` |
| `weixin` | `String` | 源自 `UserVO` |
| `qq` | `String` | 源自 `UserVO` |
| `position` | `String` | 源自 `UserVO` |
| `status` | `Integer` | 源自 `UserVO` |
| `remark` | `String` | 源自 `UserVO` |
| `msgType` | `String` | 源自 `UserVO` |
| `subscribe` | `String` | 源自 `UserVO` |
| `createTimeStart` | `Long` | 源自 `UserVO` |
| `createTimeEnd` | `Long` | 源自 `UserVO` |
| `updateTimeStart` | `Long` | 源自 `UserVO` |
| `updateTimeEnd` | `Long` | 源自 `UserVO` |
| `orgId` | `Long` | 源自 `UserVO` |
| `roleId` | `Long` | 源自 `UserVO` |
| `groupId` | `Long` | 源自 `UserVO` |
| `jiacnList` | `List<String>` | 源自 `UserVO` |
| `groupIds` | `List<Long>` | 源自 `UserVO` |
| `orgIds` | `List<Long>` | 源自 `UserVO` |
| `roleIds` | `List<Long>` | 源自 `UserVO` |
| `usernameLike` | `String` | 源自 `UserVO` |
| `openidLike` | `String` | 源自 `UserVO` |
| `jiacnLike` | `String` | 源自 `UserVO` |
| `phoneLike` | `String` | 源自 `UserVO` |
| `emailLike` | `String` | 源自 `UserVO` |
| `nicknameLike` | `String` | 源自 `UserVO` |
| `cityLike` | `String` | 源自 `UserVO` |
| `countryLike` | `String` | 源自 `UserVO` |
| `provinceLike` | `String` | 源自 `UserVO` |
| `referrerLike` | `String` | 源自 `UserVO` |

响应 data 说明：
- 成功时通常无 `data` 或 `data=null`。

#### POST `/user/org/change`

- 处理方法：`UserController.changeOrg`
- 响应：[JsonResult](#model-jsonresult)<Void>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `user` | body | yes | [UserVO](#model-uservo) | 请求体 |

请求体字段：

[UserVO](#model-uservo)

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `createTime` | `Long` | 源自 `UserVO` |
| `updateTime` | `Long` | 源自 `UserVO` |
| `tenantId` | `String` | 源自 `UserVO` |
| `clientId` | `String` | 源自 `UserVO` |
| `id` | `Long` | 源自 `UserVO` |
| `username` | `String` | 源自 `UserVO` |
| `password` | `String` | 源自 `UserVO` |
| `openid` | `String` | 源自 `UserVO` |
| `weixinid` | `String` | 源自 `UserVO` |
| `weiboid` | `String` | 源自 `UserVO` |
| `githubid` | `String` | 源自 `UserVO` |
| `jiacn` | `String` | 源自 `UserVO` |
| `phone` | `String` | 源自 `UserVO` |
| `email` | `String` | 源自 `UserVO` |
| `sex` | `Integer` | 源自 `UserVO` |
| `nickname` | `String` | 源自 `UserVO` |
| `avatar` | `String` | 源自 `UserVO` |
| `city` | `String` | 源自 `UserVO` |
| `country` | `String` | 源自 `UserVO` |
| `province` | `String` | 源自 `UserVO` |
| `location` | `String` | 源自 `UserVO` |
| `latitude` | `String` | 源自 `UserVO` |
| `longitude` | `String` | 源自 `UserVO` |
| `point` | `Integer` | 源自 `UserVO` |
| `referrer` | `String` | 源自 `UserVO` |
| `birthday` | `LocalDate` | 源自 `UserVO` |
| `tel` | `String` | 源自 `UserVO` |
| `weixin` | `String` | 源自 `UserVO` |
| `qq` | `String` | 源自 `UserVO` |
| `position` | `String` | 源自 `UserVO` |
| `status` | `Integer` | 源自 `UserVO` |
| `remark` | `String` | 源自 `UserVO` |
| `msgType` | `String` | 源自 `UserVO` |
| `subscribe` | `String` | 源自 `UserVO` |
| `createTimeStart` | `Long` | 源自 `UserVO` |
| `createTimeEnd` | `Long` | 源自 `UserVO` |
| `updateTimeStart` | `Long` | 源自 `UserVO` |
| `updateTimeEnd` | `Long` | 源自 `UserVO` |
| `orgId` | `Long` | 源自 `UserVO` |
| `roleId` | `Long` | 源自 `UserVO` |
| `groupId` | `Long` | 源自 `UserVO` |
| `jiacnList` | `List<String>` | 源自 `UserVO` |
| `groupIds` | `List<Long>` | 源自 `UserVO` |
| `orgIds` | `List<Long>` | 源自 `UserVO` |
| `roleIds` | `List<Long>` | 源自 `UserVO` |
| `usernameLike` | `String` | 源自 `UserVO` |
| `openidLike` | `String` | 源自 `UserVO` |
| `jiacnLike` | `String` | 源自 `UserVO` |
| `phoneLike` | `String` | 源自 `UserVO` |
| `emailLike` | `String` | 源自 `UserVO` |
| `nicknameLike` | `String` | 源自 `UserVO` |
| `cityLike` | `String` | 源自 `UserVO` |
| `countryLike` | `String` | 源自 `UserVO` |
| `provinceLike` | `String` | 源自 `UserVO` |
| `referrerLike` | `String` | 源自 `UserVO` |

响应 data 说明：
- 成功时通常无 `data` 或 `data=null`。

#### GET `/user/my`

- 处理方法：`UserController.userInfo`
- 响应：[JsonResult](#model-jsonresult)<[UserEntity](#model-userentity)>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| - | - | - | - | 无入参 |

响应 data 说明：
- 按 `JsonResult<UserEntity>` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

#### GET `/user/password/change`

- 处理方法：`UserController.changePassword`
- 响应：[JsonResult](#model-jsonresult)<Void>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `userId` | query | yes | Long | 查询/表单参数 |
| `oldPassword` | query | yes | String | 查询/表单参数 |
| `newPassword` | query | yes | String | 查询/表单参数 |

响应 data 说明：
- 成功时通常无 `data` 或 `data=null`。

#### GET `/user/password/reset`

- 处理方法：`UserController.resetPassword`
- 响应：[JsonResult](#model-jsonresult)<Void>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `phone` | query | yes | String | 查询/表单参数 |
| `smsCode` | query | yes | String | 查询/表单参数 |
| `newPassword` | query | yes | String | 查询/表单参数 |

响应 data 说明：
- 成功时通常无 `data` 或 `data=null`。

#### POST `/user/batch/import`

- 处理方法：`UserController.batchImport`
- 响应：[JsonResult](#model-jsonresult)<Void>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `file` | query/form | yes | MultipartFile | 查询/表单参数 |

响应 data 说明：
- 成功时通常无 `data` 或 `data=null`。

#### GET `/user/position/change`

- 处理方法：`UserController.changePosition`
- 响应：[JsonResult](#model-jsonresult)<Void>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `position` | query | yes | Long | 查询/表单参数 |

响应 data 说明：
- 成功时通常无 `data` 或 `data=null`。

#### POST `/user/update/avatar`

- 处理方法：`UserController.updateAvatar`
- 响应：[JsonResult](#model-jsonresult)<[UserEntity](#model-userentity)>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `id` | query | yes | Long | 查询/表单参数 |
| `file` | multipart | yes | MultipartFile | 文件/表单参数 |

请求体字段：

响应 data 说明：
- 按 `JsonResult<UserEntity>` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

## 模型字段字典

下表列出本模块接口入参或出参涉及的本项目模型。未列出的第三方 SDK 类型请参考对应 SDK 定义。

### Model BaseEntity

源码：`common/jia-common-core/src/main/java/cn/jia/core/entity/BaseEntity.java`

| 字段 | Java 字段 | 类型 |
| --- | --- | --- |
| `createTime` | `createTime` | `Long` |
| `updateTime` | `updateTime` | `Long` |
| `tenantId` | `tenantId` | `String` |
| `clientId` | `clientId` | `String` |

### Model GroupEntity

源码：`user/jia-user-core/src/main/java/cn/jia/user/entity/GroupEntity.java`

| 字段 | Java 字段 | 类型 |
| --- | --- | --- |
| `createTime` | `createTime` | `Long` |
| `updateTime` | `updateTime` | `Long` |
| `tenantId` | `tenantId` | `String` |
| `clientId` | `clientId` | `String` |
| `id` | `id` | `Long` |
| `name` | `name` | `String` |
| `code` | `code` | `String` |
| `remark` | `remark` | `String` |
| `status` | `status` | `Integer` |

### Model GroupVO

源码：`user/jia-user-api/src/main/java/cn/jia/user/entity/GroupVO.java`

| 字段 | Java 字段 | 类型 |
| --- | --- | --- |
| `createTime` | `createTime` | `Long` |
| `updateTime` | `updateTime` | `Long` |
| `tenantId` | `tenantId` | `String` |
| `clientId` | `clientId` | `String` |
| `id` | `id` | `Long` |
| `name` | `name` | `String` |
| `code` | `code` | `String` |
| `remark` | `remark` | `String` |
| `status` | `status` | `Integer` |
| `nameLike` | `nameLike` | `String` |
| `codeLike` | `codeLike` | `String` |
| `remarkLike` | `remarkLike` | `String` |
| `userIds` | `userIds` | `List<Long>` |
| `roleIds` | `roleIds` | `List<Long>` |

### Model JsonRequestPage

源码：`common/jia-common-core/src/main/java/cn/jia/core/entity/JsonRequestPage.java`

| 字段 | Java 字段 | 类型 |
| --- | --- | --- |
| `draw` | `draw` | `Integer` |
| `pageNum` | `pageNum` | `Integer` |
| `pageSize` | `pageSize` | `Integer` |
| `search` | `search` | `T` |
| `extra_search` | `extraSearch` | `String` |
| `orderBy` | `orderBy` | `String` |

### Model JsonResult

源码：`common/jia-common-core/src/main/java/cn/jia/core/entity/JsonResult.java`

| 字段 | Java 字段 | 类型 |
| --- | --- | --- |
| `status` | `status` | `int` |
| `location` | `location` | `String` |
| `data` | `data` | `T` |

### Model JsonResultPage

源码：`common/jia-common-core/src/main/java/cn/jia/core/entity/JsonResultPage.java`

| 字段 | Java 字段 | 类型 |
| --- | --- | --- |
| `pageNum` | `pageNum` | `Integer` |
| `total` | `total` | `Long` |
| `status` | `status` | `int` |
| `data` | `data` | `List<T>` |

### Model MsgEntity

源码：`user/jia-user-core/src/main/java/cn/jia/user/entity/MsgEntity.java`

| 字段 | Java 字段 | 类型 |
| --- | --- | --- |
| `createTime` | `createTime` | `Long` |
| `updateTime` | `updateTime` | `Long` |
| `tenantId` | `tenantId` | `String` |
| `clientId` | `clientId` | `String` |
| `id` | `id` | `Long` |
| `title` | `title` | `String` |
| `content` | `content` | `String` |
| `url` | `url` | `String` |
| `type` | `type` | `String` |
| `userId` | `userId` | `Long` |
| `status` | `status` | `Integer` |

### Model OrgEntity

源码：`user/jia-user-core/src/main/java/cn/jia/user/entity/OrgEntity.java`

| 字段 | Java 字段 | 类型 |
| --- | --- | --- |
| `createTime` | `createTime` | `Long` |
| `updateTime` | `updateTime` | `Long` |
| `tenantId` | `tenantId` | `String` |
| `clientId` | `clientId` | `String` |
| `id` | `id` | `Long` |
| `name` | `name` | `String` |
| `pId` | `pId` | `Long` |
| `type` | `type` | `Integer` |
| `code` | `code` | `String` |
| `remark` | `remark` | `String` |
| `director` | `director` | `String` |
| `logo` | `logo` | `String` |
| `logoIcon` | `logoIcon` | `String` |
| `status` | `status` | `Integer` |

### Model OrgVO

源码：`user/jia-user-api/src/main/java/cn/jia/user/entity/OrgVO.java`

| 字段 | Java 字段 | 类型 |
| --- | --- | --- |
| `createTime` | `createTime` | `Long` |
| `updateTime` | `updateTime` | `Long` |
| `tenantId` | `tenantId` | `String` |
| `clientId` | `clientId` | `String` |
| `id` | `id` | `Long` |
| `name` | `name` | `String` |
| `pId` | `pId` | `Long` |
| `type` | `type` | `Integer` |
| `code` | `code` | `String` |
| `remark` | `remark` | `String` |
| `director` | `director` | `String` |
| `logo` | `logo` | `String` |
| `logoIcon` | `logoIcon` | `String` |
| `status` | `status` | `Integer` |
| `nameLike` | `nameLike` | `String` |
| `codeLike` | `codeLike` | `String` |
| `remarkLike` | `remarkLike` | `String` |
| `userIds` | `userIds` | `List<Long>` |
| `orgIds` | `orgIds` | `List<Long>` |
| `directorNames` | `directorNames` | `String` |

### Model PermsEntity

源码：`user/jia-user-core/src/main/java/cn/jia/user/entity/PermsEntity.java`

| 字段 | Java 字段 | 类型 |
| --- | --- | --- |
| `createTime` | `createTime` | `Long` |
| `updateTime` | `updateTime` | `Long` |
| `tenantId` | `tenantId` | `String` |
| `clientId` | `clientId` | `String` |
| `id` | `id` | `Long` |
| `module` | `module` | `String` |
| `func` | `func` | `String` |
| `url` | `url` | `String` |
| `description` | `description` | `String` |
| `source` | `source` | `Integer` |
| `level` | `level` | `Integer` |
| `status` | `status` | `Integer` |

### Model RoleEntity

源码：`user/jia-user-core/src/main/java/cn/jia/user/entity/RoleEntity.java`

| 字段 | Java 字段 | 类型 |
| --- | --- | --- |
| `createTime` | `createTime` | `Long` |
| `updateTime` | `updateTime` | `Long` |
| `tenantId` | `tenantId` | `String` |
| `clientId` | `clientId` | `String` |
| `id` | `id` | `Long` |
| `name` | `name` | `String` |
| `code` | `code` | `String` |
| `remark` | `remark` | `String` |
| `status` | `status` | `Integer` |

### Model RoleVO

源码：`user/jia-user-api/src/main/java/cn/jia/user/entity/RoleVO.java`

| 字段 | Java 字段 | 类型 |
| --- | --- | --- |
| `createTime` | `createTime` | `Long` |
| `updateTime` | `updateTime` | `Long` |
| `tenantId` | `tenantId` | `String` |
| `clientId` | `clientId` | `String` |
| `id` | `id` | `Long` |
| `name` | `name` | `String` |
| `code` | `code` | `String` |
| `remark` | `remark` | `String` |
| `status` | `status` | `Integer` |
| `nameLike` | `nameLike` | `String` |
| `codeLike` | `codeLike` | `String` |
| `remarkLike` | `remarkLike` | `String` |
| `userIds` | `userIds` | `List<Long>` |
| `permsIds` | `permsIds` | `List<Long>` |
| `groupIds` | `groupIds` | `List<Long>` |

### Model UserEntity

源码：`user/jia-user-core/src/main/java/cn/jia/user/entity/UserEntity.java`

| 字段 | Java 字段 | 类型 |
| --- | --- | --- |
| `createTime` | `createTime` | `Long` |
| `updateTime` | `updateTime` | `Long` |
| `tenantId` | `tenantId` | `String` |
| `clientId` | `clientId` | `String` |
| `id` | `id` | `Long` |
| `username` | `username` | `String` |
| `password` | `password` | `String` |
| `openid` | `openid` | `String` |
| `weixinid` | `weixinid` | `String` |
| `weiboid` | `weiboid` | `String` |
| `githubid` | `githubid` | `String` |
| `jiacn` | `jiacn` | `String` |
| `phone` | `phone` | `String` |
| `email` | `email` | `String` |
| `sex` | `sex` | `Integer` |
| `nickname` | `nickname` | `String` |
| `avatar` | `avatar` | `String` |
| `city` | `city` | `String` |
| `country` | `country` | `String` |
| `province` | `province` | `String` |
| `location` | `location` | `String` |
| `latitude` | `latitude` | `String` |
| `longitude` | `longitude` | `String` |
| `point` | `point` | `Integer` |
| `referrer` | `referrer` | `String` |
| `birthday` | `birthday` | `LocalDate` |
| `tel` | `tel` | `String` |
| `weixin` | `weixin` | `String` |
| `qq` | `qq` | `String` |
| `position` | `position` | `String` |
| `status` | `status` | `Integer` |
| `remark` | `remark` | `String` |
| `msgType` | `msgType` | `String` |
| `subscribe` | `subscribe` | `String` |

### Model UserVO

源码：`user/jia-user-api/src/main/java/cn/jia/user/entity/UserVO.java`

| 字段 | Java 字段 | 类型 |
| --- | --- | --- |
| `createTime` | `createTime` | `Long` |
| `updateTime` | `updateTime` | `Long` |
| `tenantId` | `tenantId` | `String` |
| `clientId` | `clientId` | `String` |
| `id` | `id` | `Long` |
| `username` | `username` | `String` |
| `password` | `password` | `String` |
| `openid` | `openid` | `String` |
| `weixinid` | `weixinid` | `String` |
| `weiboid` | `weiboid` | `String` |
| `githubid` | `githubid` | `String` |
| `jiacn` | `jiacn` | `String` |
| `phone` | `phone` | `String` |
| `email` | `email` | `String` |
| `sex` | `sex` | `Integer` |
| `nickname` | `nickname` | `String` |
| `avatar` | `avatar` | `String` |
| `city` | `city` | `String` |
| `country` | `country` | `String` |
| `province` | `province` | `String` |
| `location` | `location` | `String` |
| `latitude` | `latitude` | `String` |
| `longitude` | `longitude` | `String` |
| `point` | `point` | `Integer` |
| `referrer` | `referrer` | `String` |
| `birthday` | `birthday` | `LocalDate` |
| `tel` | `tel` | `String` |
| `weixin` | `weixin` | `String` |
| `qq` | `qq` | `String` |
| `position` | `position` | `String` |
| `status` | `status` | `Integer` |
| `remark` | `remark` | `String` |
| `msgType` | `msgType` | `String` |
| `subscribe` | `subscribe` | `String` |
| `createTimeStart` | `createTimeStart` | `Long` |
| `createTimeEnd` | `createTimeEnd` | `Long` |
| `updateTimeStart` | `updateTimeStart` | `Long` |
| `updateTimeEnd` | `updateTimeEnd` | `Long` |
| `orgId` | `orgId` | `Long` |
| `roleId` | `roleId` | `Long` |
| `groupId` | `groupId` | `Long` |
| `jiacnList` | `jiacnList` | `List<String>` |
| `groupIds` | `groupIds` | `List<Long>` |
| `orgIds` | `orgIds` | `List<Long>` |
| `roleIds` | `roleIds` | `List<Long>` |
| `usernameLike` | `usernameLike` | `String` |
| `openidLike` | `openidLike` | `String` |
| `jiacnLike` | `jiacnLike` | `String` |
| `phoneLike` | `phoneLike` | `String` |
| `emailLike` | `emailLike` | `String` |
| `nicknameLike` | `nicknameLike` | `String` |
| `cityLike` | `cityLike` | `String` |
| `countryLike` | `countryLike` | `String` |
| `provinceLike` | `provinceLike` | `String` |
| `referrerLike` | `referrerLike` | `String` |
