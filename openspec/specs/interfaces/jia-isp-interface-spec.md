# ISP 模块接口文档

来源模块：`isp`

本文面向前端对接，每个接口包含请求方式、地址、参数位置、必填性、请求体字段和响应结构。

## 通用响应结构

- 单对象或操作结果：`JsonResult<T>`，字段：`code`、`msg`、`status`、`location`、`data`。
- 分页列表：`JsonResultPage<T>`，字段：`code`、`msg`、`status`、`pageNum`、`total`、`data`（数组）。
- 成功码默认为 `E0`，HTTP 状态通常为 `200`。

## 接口汇总

| 请求方式 | 地址 | Controller | 响应 |
| --- | --- | --- | --- |
| POST | `/car/list/brand` | `CarController.listBrand` | JsonResultPage<[CarBrandEntity](#model-carbrandentity)> |
| GET | `/car/brand/get` | `CarController.findBrandById` | JsonResult<[CarBrandEntity](#model-carbrandentity)> |
| POST | `/car/brand/create` | `CarController.createBrand` | JsonResult<record> |
| POST | `/car/brand/update` | `CarController.updateBrand` | JsonResult<record> |
| GET | `/car/brand/delete` | `CarController.deleteBrand` | JsonResult<Void> |
| POST | `/car/list/brandAudi` | `CarController.listBrandAudi` | JsonResultPage<[CarBrandAudiEntity](#model-carbrandaudientity)> |
| GET | `/car/brandAudi/get` | `CarController.findBrandAudiById` | JsonResult<[CarBrandAudiEntity](#model-carbrandaudientity)> |
| POST | `/car/brandAudi/create` | `CarController.createBrandAudi` | JsonResult<record> |
| POST | `/car/brandAudi/update` | `CarController.updateBrandAudi` | JsonResult<record> |
| GET | `/car/brandAudi/delete` | `CarController.deleteBrandAudi` | JsonResult<Void> |
| POST | `/car/list/brandVersion` | `CarController.listBrandVersion` | JsonResultPage<[CarBrandVersionEntity](#model-carbrandversionentity)> |
| GET | `/car/brandVersion/get` | `CarController.findBrandVersionById` | JsonResult<[CarBrandVersionEntity](#model-carbrandversionentity)> |
| POST | `/car/brandVersion/create` | `CarController.createBrandVersion` | JsonResult<record> |
| POST | `/car/brandVersion/update` | `CarController.updateBrandVersion` | JsonResult<record> |
| GET | `/car/brandVersion/delete` | `CarController.deleteBrandVersion` | JsonResult<Void> |
| POST | `/car/list/brandMf` | `CarController.listBrandMf` | JsonResultPage<[CarBrandMfEntity](#model-carbrandmfentity)> |
| GET | `/car/brandMf/get` | `CarController.findBrandMfById` | JsonResult<[CarBrandMfEntity](#model-carbrandmfentity)> |
| POST | `/car/brandMf/create` | `CarController.createBrandMf` | JsonResult<record> |
| POST | `/car/brandMf/update` | `CarController.updateBrandMf` | JsonResult<record> |
| GET | `/car/brandMf/delete` | `CarController.deleteBrandMf` | JsonResult<Void> |
| POST | `/cms/table/list` | `CmsController.listTable` | JsonResultPage<[CmsTableEntity](#model-cmstableentity)> |
| GET | `/cms/table/get` | `CmsController.findTableById` | JsonResult<[CmsTableEntity](#model-cmstableentity)> |
| POST | `/cms/table/create` | `CmsController.createTable` | JsonResult<record> |
| POST | `/cms/table/update` | `CmsController.updateTable` | JsonResult<record> |
| GET | `/cms/table/delete` | `CmsController.deleteTable` | JsonResult<Void> |
| POST | `/cms/column/list` | `CmsController.listColumn` | JsonResultPage<[CmsColumnEntity](#model-cmscolumnentity)> |
| GET | `/cms/column/get` | `CmsController.findColumnById` | JsonResult<[CmsColumnEntity](#model-cmscolumnentity)> |
| POST | `/cms/column/create` | `CmsController.createColumn` | JsonResult<record> |
| POST | `/cms/column/update` | `CmsController.updateColumn` | JsonResult<record> |
| GET | `/cms/column/delete` | `CmsController.deleteColumn` | JsonResult<Void> |
| GET | `/cms/config/get` | `CmsController.findConfig` | JsonResult<[CmsConfigEntity](#model-cmsconfigentity)> |
| POST | `/cms/config/update` | `CmsController.updateConfig` | JsonResult<config> |
| POST | `/cms/row/list` | `CmsController.listRow` | JsonResultPage<Map<String, Object>> |
| POST | `/cms/row/get` | `CmsController.findRowById` | JsonResult<Map<String, Object>> |
| POST | `/cms/row/create` | `CmsController.createRow` | JsonResult<record> |
| POST | `/cms/row/update` | `CmsController.updateRow` | JsonResult<record> |
| POST | `/cms/row/delete` | `CmsController.deleteRow` | JsonResult<Void> |
| GET | `/file/res/{uri:.+}/**` | `FileController.findByURI` | byte[] |
| POST | `/isp/server/list` | `IspController.listServer` | JsonResultPage<[IspServerEntity](#model-ispserverentity)> |
| GET | `/isp/server/get` | `IspController.findServerById` | JsonResult<[IspServerEntity](#model-ispserverentity)> |
| POST | `/isp/server/create` | `IspController.createServer` | JsonResult<Void> |
| POST | `/isp/server/update` | `IspController.updateServer` | JsonResult<record> |
| GET | `/isp/server/delete` | `IspController.deleteServer` | JsonResult<Void> |
| GET | `/isp/server/ssl/refresh` | `IspController.refreshSSL` | JsonResult<Void> |
| GET | `/isp/domain/record/delete` | `IspController.refreshSSL` | JsonResult<Void> |
| GET | `/isp/dns/hosts` | `IspController.hosts` | Object |
| ANY | `/isp/dns/update_record` | `IspController.updateDnsRecord` | JsonResult<request, "isp.dns.update.success"> |
| GET | `/ldap/user/get` | `LdapController.userFind` | JsonResult<LdapUser> |
| POST | `/ldap/user/search` | `LdapController.userSearch` | JsonResult<List<LdapUser>> |
| POST | `/ldap/user/create` | `LdapController.userCreate` | JsonResult<user> |
| POST | `/ldap/user/update` | `LdapController.userUpdate` | JsonResult<Void> |
| GET | `/ldap/user/delete` | `LdapController.userDelete` | JsonResult<Void> |
| GET | `/ldap/user/findAll` | `LdapController.userFindAll` | JsonResultPage<LdapUser> |
| GET | `/ldap/usergroup/findByCn` | `LdapController.userGroupFindByCn` | JsonResult<LdapUserGroup> |
| GET | `/ldap/usergroup/findAll` | `LdapController.userGroupFindAll` | JsonResultPage<[LdapUserGroupDTO](#model-ldapusergroupdto)> |
| POST | `/ldap/usergroup/create` | `LdapController.userGroupCreate` | JsonResult<Void> |
| POST | `/ldap/usergroup/update` | `LdapController.userGroupUpdate` | JsonResult<Void> |
| POST | `/ldap/usergroup/member/add` | `LdapController.userGroupMemberAdd` | JsonResult<Void> |
| POST | `/ldap/usergroup/member/delete` | `LdapController.userGroupMemberDelete` | JsonResult<Void> |
| GET | `/ldap/usergroup/delete` | `LdapController.userGroupDelete` | JsonResult<Void> |
| GET | `/ldap/account/findByUid` | `LdapController.accountFindByUid` | JsonResult<LdapAccount> |

## 接口明细

### CarController `/car`

源码：`isp/jia-isp-service/src/main/java/cn/jia/isp/api/CarController.java`

#### POST `/car/list/brand`

- 处理方法：`CarController.listBrand`
- 响应：[JsonResult](#model-jsonresult)<Void>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `page` | body | yes | JsonRequestPage<[CarBrandEntity](#model-carbrandentity)> | 请求体 |

请求体字段：

[CarBrandEntity](#model-carbrandentity)

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `createTime` | `Long` | 源自 `CarBrandEntity` |
| `updateTime` | `Long` | 源自 `CarBrandEntity` |
| `tenantId` | `String` | 源自 `CarBrandEntity` |
| `clientId` | `String` | 源自 `CarBrandEntity` |
| `id` | `Long` | 源自 `CarBrandEntity` |
| `brand` | `String` | 源自 `CarBrandEntity` |
| `abbr` | `String` | 源自 `CarBrandEntity` |
| `initials` | `String` | 源自 `CarBrandEntity` |
| `addTime` | `Long` | 源自 `CarBrandEntity` |
| `upTime` | `Long` | 源自 `CarBrandEntity` |

响应 data 说明：
- 成功时通常无 `data` 或 `data=null`。

#### GET `/car/brand/get`

- 处理方法：`CarController.findBrandById`
- 响应：[JsonResult](#model-jsonresult)<[CarBrandEntity](#model-carbrandentity)>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `id` | query | yes | Long | 查询/表单参数 |

响应 data 说明：
- 按 `JsonResult<CarBrandEntity>` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

#### POST `/car/brand/create`

- 处理方法：`CarController.createBrand`
- 响应：[JsonResult](#model-jsonresult)<[CarBrandEntity](#model-carbrandentity)>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `record` | body | yes | [CarBrandEntity](#model-carbrandentity) | 请求体 |

请求体字段：

[CarBrandEntity](#model-carbrandentity)

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `createTime` | `Long` | 源自 `CarBrandEntity` |
| `updateTime` | `Long` | 源自 `CarBrandEntity` |
| `tenantId` | `String` | 源自 `CarBrandEntity` |
| `clientId` | `String` | 源自 `CarBrandEntity` |
| `id` | `Long` | 源自 `CarBrandEntity` |
| `brand` | `String` | 源自 `CarBrandEntity` |
| `abbr` | `String` | 源自 `CarBrandEntity` |
| `initials` | `String` | 源自 `CarBrandEntity` |
| `addTime` | `Long` | 源自 `CarBrandEntity` |
| `upTime` | `Long` | 源自 `CarBrandEntity` |

响应 data 说明：
- 按 `JsonResult<CarBrandEntity>` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

#### POST `/car/brand/update`

- 处理方法：`CarController.updateBrand`
- 响应：[JsonResult](#model-jsonresult)<[CarBrandEntity](#model-carbrandentity)>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `record` | body | yes | [CarBrandEntity](#model-carbrandentity) | 请求体 |

请求体字段：

[CarBrandEntity](#model-carbrandentity)

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `createTime` | `Long` | 源自 `CarBrandEntity` |
| `updateTime` | `Long` | 源自 `CarBrandEntity` |
| `tenantId` | `String` | 源自 `CarBrandEntity` |
| `clientId` | `String` | 源自 `CarBrandEntity` |
| `id` | `Long` | 源自 `CarBrandEntity` |
| `brand` | `String` | 源自 `CarBrandEntity` |
| `abbr` | `String` | 源自 `CarBrandEntity` |
| `initials` | `String` | 源自 `CarBrandEntity` |
| `addTime` | `Long` | 源自 `CarBrandEntity` |
| `upTime` | `Long` | 源自 `CarBrandEntity` |

响应 data 说明：
- 按 `JsonResult<CarBrandEntity>` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

#### GET `/car/brand/delete`

- 处理方法：`CarController.deleteBrand`
- 响应：[JsonResult](#model-jsonresult)<Void>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `id` | query | yes | Long | 查询/表单参数 |

响应 data 说明：
- 成功时通常无 `data` 或 `data=null`。

#### POST `/car/list/brandAudi`

- 处理方法：`CarController.listBrandAudi`
- 响应：[JsonResult](#model-jsonresult)<Void>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `page` | body | yes | JsonRequestPage<[CarBrandAudiEntity](#model-carbrandaudientity)> | 请求体 |

请求体字段：

[CarBrandAudiEntity](#model-carbrandaudientity)

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `createTime` | `Long` | 源自 `CarBrandAudiEntity` |
| `updateTime` | `Long` | 源自 `CarBrandAudiEntity` |
| `tenantId` | `String` | 源自 `CarBrandAudiEntity` |
| `clientId` | `String` | 源自 `CarBrandAudiEntity` |
| `id` | `Long` | 源自 `CarBrandAudiEntity` |
| `brandMf` | `Long` | 源自 `CarBrandAudiEntity` |
| `carSeries` | `String` | 源自 `CarBrandAudiEntity` |
| `abbr` | `String` | 源自 `CarBrandAudiEntity` |
| `initials` | `String` | 源自 `CarBrandAudiEntity` |

响应 data 说明：
- 成功时通常无 `data` 或 `data=null`。

#### GET `/car/brandAudi/get`

- 处理方法：`CarController.findBrandAudiById`
- 响应：[JsonResult](#model-jsonresult)<[CarBrandAudiEntity](#model-carbrandaudientity)>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `id` | query | yes | Long | 查询/表单参数 |

响应 data 说明：
- 按 `JsonResult<CarBrandAudiEntity>` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

#### POST `/car/brandAudi/create`

- 处理方法：`CarController.createBrandAudi`
- 响应：[JsonResult](#model-jsonresult)<[CarBrandAudiEntity](#model-carbrandaudientity)>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `record` | body | yes | [CarBrandAudiEntity](#model-carbrandaudientity) | 请求体 |

请求体字段：

[CarBrandAudiEntity](#model-carbrandaudientity)

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `createTime` | `Long` | 源自 `CarBrandAudiEntity` |
| `updateTime` | `Long` | 源自 `CarBrandAudiEntity` |
| `tenantId` | `String` | 源自 `CarBrandAudiEntity` |
| `clientId` | `String` | 源自 `CarBrandAudiEntity` |
| `id` | `Long` | 源自 `CarBrandAudiEntity` |
| `brandMf` | `Long` | 源自 `CarBrandAudiEntity` |
| `carSeries` | `String` | 源自 `CarBrandAudiEntity` |
| `abbr` | `String` | 源自 `CarBrandAudiEntity` |
| `initials` | `String` | 源自 `CarBrandAudiEntity` |

响应 data 说明：
- 按 `JsonResult<CarBrandAudiEntity>` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

#### POST `/car/brandAudi/update`

- 处理方法：`CarController.updateBrandAudi`
- 响应：[JsonResult](#model-jsonresult)<[CarBrandAudiEntity](#model-carbrandaudientity)>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `record` | body | yes | [CarBrandAudiEntity](#model-carbrandaudientity) | 请求体 |

请求体字段：

[CarBrandAudiEntity](#model-carbrandaudientity)

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `createTime` | `Long` | 源自 `CarBrandAudiEntity` |
| `updateTime` | `Long` | 源自 `CarBrandAudiEntity` |
| `tenantId` | `String` | 源自 `CarBrandAudiEntity` |
| `clientId` | `String` | 源自 `CarBrandAudiEntity` |
| `id` | `Long` | 源自 `CarBrandAudiEntity` |
| `brandMf` | `Long` | 源自 `CarBrandAudiEntity` |
| `carSeries` | `String` | 源自 `CarBrandAudiEntity` |
| `abbr` | `String` | 源自 `CarBrandAudiEntity` |
| `initials` | `String` | 源自 `CarBrandAudiEntity` |

响应 data 说明：
- 按 `JsonResult<CarBrandAudiEntity>` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

#### GET `/car/brandAudi/delete`

- 处理方法：`CarController.deleteBrandAudi`
- 响应：[JsonResult](#model-jsonresult)<Void>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `id` | query | yes | Long | 查询/表单参数 |

响应 data 说明：
- 成功时通常无 `data` 或 `data=null`。

#### POST `/car/list/brandVersion`

- 处理方法：`CarController.listBrandVersion`
- 响应：[JsonResult](#model-jsonresult)<Void>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `page` | body | yes | JsonRequestPage<[CarBrandVersionEntity](#model-carbrandversionentity)> | 请求体 |

请求体字段：

[CarBrandVersionEntity](#model-carbrandversionentity)

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `createTime` | `Long` | 源自 `CarBrandVersionEntity` |
| `updateTime` | `Long` | 源自 `CarBrandVersionEntity` |
| `tenantId` | `String` | 源自 `CarBrandVersionEntity` |
| `clientId` | `String` | 源自 `CarBrandVersionEntity` |
| `id` | `Long` | 源自 `CarBrandVersionEntity` |
| `audi` | `Long` | 源自 `CarBrandVersionEntity` |
| `version` | `String` | 源自 `CarBrandVersionEntity` |
| `vName` | `String` | 源自 `CarBrandVersionEntity` |

响应 data 说明：
- 成功时通常无 `data` 或 `data=null`。

#### GET `/car/brandVersion/get`

- 处理方法：`CarController.findBrandVersionById`
- 响应：[JsonResult](#model-jsonresult)<[CarBrandVersionEntity](#model-carbrandversionentity)>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `id` | query | yes | Long | 查询/表单参数 |

响应 data 说明：
- 按 `JsonResult<CarBrandVersionEntity>` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

#### POST `/car/brandVersion/create`

- 处理方法：`CarController.createBrandVersion`
- 响应：[JsonResult](#model-jsonresult)<[CarBrandVersionEntity](#model-carbrandversionentity)>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `record` | body | yes | [CarBrandVersionEntity](#model-carbrandversionentity) | 请求体 |

请求体字段：

[CarBrandVersionEntity](#model-carbrandversionentity)

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `createTime` | `Long` | 源自 `CarBrandVersionEntity` |
| `updateTime` | `Long` | 源自 `CarBrandVersionEntity` |
| `tenantId` | `String` | 源自 `CarBrandVersionEntity` |
| `clientId` | `String` | 源自 `CarBrandVersionEntity` |
| `id` | `Long` | 源自 `CarBrandVersionEntity` |
| `audi` | `Long` | 源自 `CarBrandVersionEntity` |
| `version` | `String` | 源自 `CarBrandVersionEntity` |
| `vName` | `String` | 源自 `CarBrandVersionEntity` |

响应 data 说明：
- 按 `JsonResult<CarBrandVersionEntity>` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

#### POST `/car/brandVersion/update`

- 处理方法：`CarController.updateBrandVersion`
- 响应：[JsonResult](#model-jsonresult)<[CarBrandVersionEntity](#model-carbrandversionentity)>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `record` | body | yes | [CarBrandVersionEntity](#model-carbrandversionentity) | 请求体 |

请求体字段：

[CarBrandVersionEntity](#model-carbrandversionentity)

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `createTime` | `Long` | 源自 `CarBrandVersionEntity` |
| `updateTime` | `Long` | 源自 `CarBrandVersionEntity` |
| `tenantId` | `String` | 源自 `CarBrandVersionEntity` |
| `clientId` | `String` | 源自 `CarBrandVersionEntity` |
| `id` | `Long` | 源自 `CarBrandVersionEntity` |
| `audi` | `Long` | 源自 `CarBrandVersionEntity` |
| `version` | `String` | 源自 `CarBrandVersionEntity` |
| `vName` | `String` | 源自 `CarBrandVersionEntity` |

响应 data 说明：
- 按 `JsonResult<CarBrandVersionEntity>` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

#### GET `/car/brandVersion/delete`

- 处理方法：`CarController.deleteBrandVersion`
- 响应：[JsonResult](#model-jsonresult)<Void>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `id` | query | yes | Long | 查询/表单参数 |

响应 data 说明：
- 成功时通常无 `data` 或 `data=null`。

#### POST `/car/list/brandMf`

- 处理方法：`CarController.listBrandMf`
- 响应：[JsonResult](#model-jsonresult)<Void>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `page` | body | yes | JsonRequestPage<[CarBrandMfEntity](#model-carbrandmfentity)> | 请求体 |

请求体字段：

[CarBrandMfEntity](#model-carbrandmfentity)

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `createTime` | `Long` | 源自 `CarBrandMfEntity` |
| `updateTime` | `Long` | 源自 `CarBrandMfEntity` |
| `tenantId` | `String` | 源自 `CarBrandMfEntity` |
| `clientId` | `String` | 源自 `CarBrandMfEntity` |
| `id` | `Long` | 源自 `CarBrandMfEntity` |
| `brand` | `Long` | 源自 `CarBrandMfEntity` |
| `brandMf` | `String` | 源自 `CarBrandMfEntity` |
| `abbr` | `String` | 源自 `CarBrandMfEntity` |
| `initials` | `String` | 源自 `CarBrandMfEntity` |

响应 data 说明：
- 成功时通常无 `data` 或 `data=null`。

#### GET `/car/brandMf/get`

- 处理方法：`CarController.findBrandMfById`
- 响应：[JsonResult](#model-jsonresult)<[CarBrandMfEntity](#model-carbrandmfentity)>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `id` | query | yes | Long | 查询/表单参数 |

响应 data 说明：
- 按 `JsonResult<CarBrandMfEntity>` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

#### POST `/car/brandMf/create`

- 处理方法：`CarController.createBrandMf`
- 响应：[JsonResult](#model-jsonresult)<[CarBrandMfEntity](#model-carbrandmfentity)>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `record` | body | yes | [CarBrandMfEntity](#model-carbrandmfentity) | 请求体 |

请求体字段：

[CarBrandMfEntity](#model-carbrandmfentity)

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `createTime` | `Long` | 源自 `CarBrandMfEntity` |
| `updateTime` | `Long` | 源自 `CarBrandMfEntity` |
| `tenantId` | `String` | 源自 `CarBrandMfEntity` |
| `clientId` | `String` | 源自 `CarBrandMfEntity` |
| `id` | `Long` | 源自 `CarBrandMfEntity` |
| `brand` | `Long` | 源自 `CarBrandMfEntity` |
| `brandMf` | `String` | 源自 `CarBrandMfEntity` |
| `abbr` | `String` | 源自 `CarBrandMfEntity` |
| `initials` | `String` | 源自 `CarBrandMfEntity` |

响应 data 说明：
- 按 `JsonResult<CarBrandMfEntity>` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

#### POST `/car/brandMf/update`

- 处理方法：`CarController.updateBrandMf`
- 响应：[JsonResult](#model-jsonresult)<[CarBrandMfEntity](#model-carbrandmfentity)>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `record` | body | yes | [CarBrandMfEntity](#model-carbrandmfentity) | 请求体 |

请求体字段：

[CarBrandMfEntity](#model-carbrandmfentity)

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `createTime` | `Long` | 源自 `CarBrandMfEntity` |
| `updateTime` | `Long` | 源自 `CarBrandMfEntity` |
| `tenantId` | `String` | 源自 `CarBrandMfEntity` |
| `clientId` | `String` | 源自 `CarBrandMfEntity` |
| `id` | `Long` | 源自 `CarBrandMfEntity` |
| `brand` | `Long` | 源自 `CarBrandMfEntity` |
| `brandMf` | `String` | 源自 `CarBrandMfEntity` |
| `abbr` | `String` | 源自 `CarBrandMfEntity` |
| `initials` | `String` | 源自 `CarBrandMfEntity` |

响应 data 说明：
- 按 `JsonResult<CarBrandMfEntity>` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

#### GET `/car/brandMf/delete`

- 处理方法：`CarController.deleteBrandMf`
- 响应：[JsonResult](#model-jsonresult)<Void>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `id` | query | yes | Long | 查询/表单参数 |

响应 data 说明：
- 成功时通常无 `data` 或 `data=null`。

### CmsController `/cms`

源码：`isp/jia-isp-service/src/main/java/cn/jia/isp/api/CmsController.java`

#### POST `/cms/table/list`

- 处理方法：`CmsController.listTable`
- 响应：[JsonResult](#model-jsonresult)<Void>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `page` | body | yes | JsonRequestPage<[CmsTableEntity](#model-cmstableentity)> | 请求体 |

请求体字段：

[CmsTableEntity](#model-cmstableentity)

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `createTime` | `Long` | 源自 `CmsTableEntity` |
| `updateTime` | `Long` | 源自 `CmsTableEntity` |
| `tenantId` | `String` | 源自 `CmsTableEntity` |
| `clientId` | `String` | 源自 `CmsTableEntity` |
| `id` | `Long` | 源自 `CmsTableEntity` |
| `name` | `String` | 源自 `CmsTableEntity` |
| `remark` | `String` | 源自 `CmsTableEntity` |

响应 data 说明：
- 成功时通常无 `data` 或 `data=null`。

#### GET `/cms/table/get`

- 处理方法：`CmsController.findTableById`
- 响应：[JsonResult](#model-jsonresult)<[CmsTableEntity](#model-cmstableentity)>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `id` | query | yes | Long | 查询/表单参数 |

响应 data 说明：
- 按 `JsonResult<CmsTableEntity>` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

#### POST `/cms/table/create`

- 处理方法：`CmsController.createTable`
- 响应：[JsonResult](#model-jsonresult)<[CmsTableDTO](#model-cmstabledto)>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `record` | body | yes | [CmsTableDTO](#model-cmstabledto) | 请求体 |

请求体字段：

[CmsTableDTO](#model-cmstabledto)

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `createTime` | `Long` | 源自 `CmsTableDTO` |
| `updateTime` | `Long` | 源自 `CmsTableDTO` |
| `tenantId` | `String` | 源自 `CmsTableDTO` |
| `clientId` | `String` | 源自 `CmsTableDTO` |
| `id` | `Long` | 源自 `CmsTableDTO` |
| `name` | `String` | 源自 `CmsTableDTO` |
| `remark` | `String` | 源自 `CmsTableDTO` |
| `rows` | `List<CmsRowEntity>` | 源自 `CmsTableDTO` |
| `columns` | `List<CmsColumnDTO>` | 源自 `CmsTableDTO` |

响应 data 说明：
- 按 `JsonResult<CmsTableDTO>` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

#### POST `/cms/table/update`

- 处理方法：`CmsController.updateTable`
- 响应：[JsonResult](#model-jsonresult)<[CmsTableEntity](#model-cmstableentity)>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `record` | body | yes | [CmsTableEntity](#model-cmstableentity) | 请求体 |

请求体字段：

[CmsTableEntity](#model-cmstableentity)

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `createTime` | `Long` | 源自 `CmsTableEntity` |
| `updateTime` | `Long` | 源自 `CmsTableEntity` |
| `tenantId` | `String` | 源自 `CmsTableEntity` |
| `clientId` | `String` | 源自 `CmsTableEntity` |
| `id` | `Long` | 源自 `CmsTableEntity` |
| `name` | `String` | 源自 `CmsTableEntity` |
| `remark` | `String` | 源自 `CmsTableEntity` |

响应 data 说明：
- 按 `JsonResult<CmsTableEntity>` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

#### GET `/cms/table/delete`

- 处理方法：`CmsController.deleteTable`
- 响应：[JsonResult](#model-jsonresult)<Void>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `id` | query | yes | Long | 查询/表单参数 |

响应 data 说明：
- 成功时通常无 `data` 或 `data=null`。

#### POST `/cms/column/list`

- 处理方法：`CmsController.listColumn`
- 响应：[JsonResult](#model-jsonresult)<Void>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `page` | body | yes | JsonRequestPage<[CmsColumnEntity](#model-cmscolumnentity)> | 请求体 |

请求体字段：

[CmsColumnEntity](#model-cmscolumnentity)

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `createTime` | `Long` | 源自 `CmsColumnEntity` |
| `updateTime` | `Long` | 源自 `CmsColumnEntity` |
| `tenantId` | `String` | 源自 `CmsColumnEntity` |
| `clientId` | `String` | 源自 `CmsColumnEntity` |
| `id` | `Long` | 源自 `CmsColumnEntity` |
| `tableId` | `Long` | 源自 `CmsColumnEntity` |
| `name` | `String` | 源自 `CmsColumnEntity` |
| `type` | `String` | 源自 `CmsColumnEntity` |
| `precision` | `Integer` | 源自 `CmsColumnEntity` |
| `scale` | `Integer` | 源自 `CmsColumnEntity` |
| `notnull` | `Integer` | 源自 `CmsColumnEntity` |
| `defaultValue` | `String` | 源自 `CmsColumnEntity` |
| `selectRange` | `String` | 源自 `CmsColumnEntity` |
| `isSearch` | `Integer` | 源自 `CmsColumnEntity` |
| `isList` | `Integer` | 源自 `CmsColumnEntity` |
| `remark` | `String` | 源自 `CmsColumnEntity` |

响应 data 说明：
- 成功时通常无 `data` 或 `data=null`。

#### GET `/cms/column/get`

- 处理方法：`CmsController.findColumnById`
- 响应：[JsonResult](#model-jsonresult)<[CmsColumnEntity](#model-cmscolumnentity)>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `id` | query | yes | Long | 查询/表单参数 |

响应 data 说明：
- 按 `JsonResult<CmsColumnEntity>` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

#### POST `/cms/column/create`

- 处理方法：`CmsController.createColumn`
- 响应：[JsonResult](#model-jsonresult)<[CmsColumnEntity](#model-cmscolumnentity)>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `record` | body | yes | [CmsColumnEntity](#model-cmscolumnentity) | 请求体 |

请求体字段：

[CmsColumnEntity](#model-cmscolumnentity)

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `createTime` | `Long` | 源自 `CmsColumnEntity` |
| `updateTime` | `Long` | 源自 `CmsColumnEntity` |
| `tenantId` | `String` | 源自 `CmsColumnEntity` |
| `clientId` | `String` | 源自 `CmsColumnEntity` |
| `id` | `Long` | 源自 `CmsColumnEntity` |
| `tableId` | `Long` | 源自 `CmsColumnEntity` |
| `name` | `String` | 源自 `CmsColumnEntity` |
| `type` | `String` | 源自 `CmsColumnEntity` |
| `precision` | `Integer` | 源自 `CmsColumnEntity` |
| `scale` | `Integer` | 源自 `CmsColumnEntity` |
| `notnull` | `Integer` | 源自 `CmsColumnEntity` |
| `defaultValue` | `String` | 源自 `CmsColumnEntity` |
| `selectRange` | `String` | 源自 `CmsColumnEntity` |
| `isSearch` | `Integer` | 源自 `CmsColumnEntity` |
| `isList` | `Integer` | 源自 `CmsColumnEntity` |
| `remark` | `String` | 源自 `CmsColumnEntity` |

响应 data 说明：
- 按 `JsonResult<CmsColumnEntity>` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

#### POST `/cms/column/update`

- 处理方法：`CmsController.updateColumn`
- 响应：[JsonResult](#model-jsonresult)<[CmsColumnEntity](#model-cmscolumnentity)>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `record` | body | yes | [CmsColumnEntity](#model-cmscolumnentity) | 请求体 |

请求体字段：

[CmsColumnEntity](#model-cmscolumnentity)

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `createTime` | `Long` | 源自 `CmsColumnEntity` |
| `updateTime` | `Long` | 源自 `CmsColumnEntity` |
| `tenantId` | `String` | 源自 `CmsColumnEntity` |
| `clientId` | `String` | 源自 `CmsColumnEntity` |
| `id` | `Long` | 源自 `CmsColumnEntity` |
| `tableId` | `Long` | 源自 `CmsColumnEntity` |
| `name` | `String` | 源自 `CmsColumnEntity` |
| `type` | `String` | 源自 `CmsColumnEntity` |
| `precision` | `Integer` | 源自 `CmsColumnEntity` |
| `scale` | `Integer` | 源自 `CmsColumnEntity` |
| `notnull` | `Integer` | 源自 `CmsColumnEntity` |
| `defaultValue` | `String` | 源自 `CmsColumnEntity` |
| `selectRange` | `String` | 源自 `CmsColumnEntity` |
| `isSearch` | `Integer` | 源自 `CmsColumnEntity` |
| `isList` | `Integer` | 源自 `CmsColumnEntity` |
| `remark` | `String` | 源自 `CmsColumnEntity` |

响应 data 说明：
- 按 `JsonResult<CmsColumnEntity>` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

#### GET `/cms/column/delete`

- 处理方法：`CmsController.deleteColumn`
- 响应：[JsonResult](#model-jsonresult)<Void>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `id` | query | yes | Long | 查询/表单参数 |

响应 data 说明：
- 成功时通常无 `data` 或 `data=null`。

#### GET `/cms/config/get`

- 处理方法：`CmsController.findConfig`
- 响应：[JsonResult](#model-jsonresult)<[CmsConfigEntity](#model-cmsconfigentity)>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| - | - | - | - | 无入参 |

响应 data 说明：
- 按 `JsonResult<CmsConfigEntity>` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

#### POST `/cms/config/update`

- 处理方法：`CmsController.updateConfig`
- 响应：[JsonResult](#model-jsonresult)<[CmsConfigEntity](#model-cmsconfigentity)>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `config` | body | yes | [CmsConfigEntity](#model-cmsconfigentity) | 请求体 |

请求体字段：

[CmsConfigEntity](#model-cmsconfigentity)

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `createTime` | `Long` | 源自 `CmsConfigEntity` |
| `updateTime` | `Long` | 源自 `CmsConfigEntity` |
| `tenantId` | `String` | 源自 `CmsConfigEntity` |
| `clientId` | `String` | 源自 `CmsConfigEntity` |
| `tablePrefix` | `String` | 源自 `CmsConfigEntity` |

响应 data 说明：
- 按 `JsonResult<CmsConfigEntity>` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

#### POST `/cms/row/list`

- 处理方法：`CmsController.listRow`
- 响应：[JsonResult](#model-jsonresult)<Void>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `page` | body | yes | JsonRequestPage<[CmsRowExample](#model-cmsrowexample)> | 请求体 |

请求体字段：

[CmsRowExample](#model-cmsrowexample)

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | `Long` | 源自 `CmsRowExample` |
| `clientId` | `String` | 源自 `CmsRowExample` |
| `name` | `String` | 源自 `CmsRowExample` |
| `remark` | `String` | 源自 `CmsRowExample` |
| `rows` | `List<CmsRowEntity>` | 源自 `CmsRowExample` |
| `orderBy` | `String` | 源自 `CmsRowExample` |

响应 data 说明：
- 成功时通常无 `data` 或 `data=null`。

#### POST `/cms/row/get`

- 处理方法：`CmsController.findRowById`
- 响应：[JsonResult](#model-jsonresult)<Map<String, Object>>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `cmsTable` | body | yes | [CmsRowDTO](#model-cmsrowdto) | 请求体 |

请求体字段：

[CmsRowDTO](#model-cmsrowdto)

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | `Long` | 源自 `CmsRowDTO` |
| `clientId` | `String` | 源自 `CmsRowDTO` |
| `tableId` | `Long` | 源自 `CmsRowDTO` |
| `tableName` | `String` | 源自 `CmsRowDTO` |
| `rows` | `List<CmsRowEntity>` | 源自 `CmsRowDTO` |

响应 data 说明：
- 按 `JsonResult<Map<String, Object>>` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

#### POST `/cms/row/create`

- 处理方法：`CmsController.createRow`
- 响应：[JsonResult](#model-jsonresult)<[CmsRowDTO](#model-cmsrowdto)>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `record` | body | yes | [CmsRowDTO](#model-cmsrowdto) | 请求体 |

请求体字段：

[CmsRowDTO](#model-cmsrowdto)

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | `Long` | 源自 `CmsRowDTO` |
| `clientId` | `String` | 源自 `CmsRowDTO` |
| `tableId` | `Long` | 源自 `CmsRowDTO` |
| `tableName` | `String` | 源自 `CmsRowDTO` |
| `rows` | `List<CmsRowEntity>` | 源自 `CmsRowDTO` |

响应 data 说明：
- 按 `JsonResult<CmsRowDTO>` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

#### POST `/cms/row/update`

- 处理方法：`CmsController.updateRow`
- 响应：[JsonResult](#model-jsonresult)<[CmsRowDTO](#model-cmsrowdto)>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `record` | body | yes | [CmsRowDTO](#model-cmsrowdto) | 请求体 |

请求体字段：

[CmsRowDTO](#model-cmsrowdto)

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | `Long` | 源自 `CmsRowDTO` |
| `clientId` | `String` | 源自 `CmsRowDTO` |
| `tableId` | `Long` | 源自 `CmsRowDTO` |
| `tableName` | `String` | 源自 `CmsRowDTO` |
| `rows` | `List<CmsRowEntity>` | 源自 `CmsRowDTO` |

响应 data 说明：
- 按 `JsonResult<CmsRowDTO>` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

#### POST `/cms/row/delete`

- 处理方法：`CmsController.deleteRow`
- 响应：[JsonResult](#model-jsonresult)<Void>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `record` | body | yes | [CmsRowDTO](#model-cmsrowdto) | 请求体 |

请求体字段：

[CmsRowDTO](#model-cmsrowdto)

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | `Long` | 源自 `CmsRowDTO` |
| `clientId` | `String` | 源自 `CmsRowDTO` |
| `tableId` | `Long` | 源自 `CmsRowDTO` |
| `tableName` | `String` | 源自 `CmsRowDTO` |
| `rows` | `List<CmsRowEntity>` | 源自 `CmsRowDTO` |

响应 data 说明：
- 成功时通常无 `data` 或 `data=null`。

### FileController `/file`

源码：`isp/jia-isp-service/src/main/java/cn/jia/isp/api/FileController.java`

#### GET `/file/res/{uri:.+}/**`

- 处理方法：`FileController.findByURI`
- 响应：byte[]

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `uri` | path | yes | String | 路径参数 |

响应 data 说明：
- 按 `byte[]` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

### IspController `/isp`

源码：`isp/jia-isp-service/src/main/java/cn/jia/isp/api/IspController.java`

#### POST `/isp/server/list`

- 处理方法：`IspController.listServer`
- 响应：[JsonResult](#model-jsonresult)<Void>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `page` | body | yes | JsonRequestPage<[IspServerEntity](#model-ispserverentity)> | 请求体 |

请求体字段：

[IspServerEntity](#model-ispserverentity)

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `createTime` | `Long` | 源自 `IspServerEntity` |
| `updateTime` | `Long` | 源自 `IspServerEntity` |
| `tenantId` | `String` | 源自 `IspServerEntity` |
| `clientId` | `String` | 源自 `IspServerEntity` |
| `id` | `Long` | 源自 `IspServerEntity` |
| `serverName` | `String` | 源自 `IspServerEntity` |
| `serverDescription` | `String` | 源自 `IspServerEntity` |
| `ip` | `String` | 源自 `IspServerEntity` |
| `sshPort` | `Integer` | 源自 `IspServerEntity` |
| `sshUser` | `String` | 源自 `IspServerEntity` |
| `sshPassword` | `String` | 源自 `IspServerEntity` |
| `consolePort` | `Integer` | 源自 `IspServerEntity` |
| `consoleToken` | `String` | 源自 `IspServerEntity` |
| `ldapService` | `Integer` | 源自 `IspServerEntity` |
| `ldapPort` | `Integer` | 源自 `IspServerEntity` |
| `ldapUser` | `String` | 源自 `IspServerEntity` |
| `ldapPassword` | `String` | 源自 `IspServerEntity` |
| `ldapBase` | `String` | 源自 `IspServerEntity` |
| `smbService` | `Integer` | 源自 `IspServerEntity` |
| `smbLdapBase` | `String` | 源自 `IspServerEntity` |
| `smbLdapUser` | `String` | 源自 `IspServerEntity` |
| `smbLdapPassword` | `String` | 源自 `IspServerEntity` |
| `smbLdapUrl` | `String` | 源自 `IspServerEntity` |
| `status` | `Integer` | 源自 `IspServerEntity` |

响应 data 说明：
- 成功时通常无 `data` 或 `data=null`。

#### GET `/isp/server/get`

- 处理方法：`IspController.findServerById`
- 响应：[JsonResult](#model-jsonresult)<[IspServerEntity](#model-ispserverentity)>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `id` | query | yes | Long | 查询/表单参数 |

响应 data 说明：
- 按 `JsonResult<IspServerEntity>` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

#### POST `/isp/server/create`

- 处理方法：`IspController.createServer`
- 响应：[JsonResult](#model-jsonresult)<Void>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `record` | body | yes | [IspServerEntity](#model-ispserverentity) | 请求体 |

请求体字段：

[IspServerEntity](#model-ispserverentity)

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `createTime` | `Long` | 源自 `IspServerEntity` |
| `updateTime` | `Long` | 源自 `IspServerEntity` |
| `tenantId` | `String` | 源自 `IspServerEntity` |
| `clientId` | `String` | 源自 `IspServerEntity` |
| `id` | `Long` | 源自 `IspServerEntity` |
| `serverName` | `String` | 源自 `IspServerEntity` |
| `serverDescription` | `String` | 源自 `IspServerEntity` |
| `ip` | `String` | 源自 `IspServerEntity` |
| `sshPort` | `Integer` | 源自 `IspServerEntity` |
| `sshUser` | `String` | 源自 `IspServerEntity` |
| `sshPassword` | `String` | 源自 `IspServerEntity` |
| `consolePort` | `Integer` | 源自 `IspServerEntity` |
| `consoleToken` | `String` | 源自 `IspServerEntity` |
| `ldapService` | `Integer` | 源自 `IspServerEntity` |
| `ldapPort` | `Integer` | 源自 `IspServerEntity` |
| `ldapUser` | `String` | 源自 `IspServerEntity` |
| `ldapPassword` | `String` | 源自 `IspServerEntity` |
| `ldapBase` | `String` | 源自 `IspServerEntity` |
| `smbService` | `Integer` | 源自 `IspServerEntity` |
| `smbLdapBase` | `String` | 源自 `IspServerEntity` |
| `smbLdapUser` | `String` | 源自 `IspServerEntity` |
| `smbLdapPassword` | `String` | 源自 `IspServerEntity` |
| `smbLdapUrl` | `String` | 源自 `IspServerEntity` |
| `status` | `Integer` | 源自 `IspServerEntity` |

响应 data 说明：
- 成功时通常无 `data` 或 `data=null`。

#### POST `/isp/server/update`

- 处理方法：`IspController.updateServer`
- 响应：[JsonResult](#model-jsonresult)<[IspServerEntity](#model-ispserverentity)>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `record` | body | yes | [IspServerEntity](#model-ispserverentity) | 请求体 |

请求体字段：

[IspServerEntity](#model-ispserverentity)

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `createTime` | `Long` | 源自 `IspServerEntity` |
| `updateTime` | `Long` | 源自 `IspServerEntity` |
| `tenantId` | `String` | 源自 `IspServerEntity` |
| `clientId` | `String` | 源自 `IspServerEntity` |
| `id` | `Long` | 源自 `IspServerEntity` |
| `serverName` | `String` | 源自 `IspServerEntity` |
| `serverDescription` | `String` | 源自 `IspServerEntity` |
| `ip` | `String` | 源自 `IspServerEntity` |
| `sshPort` | `Integer` | 源自 `IspServerEntity` |
| `sshUser` | `String` | 源自 `IspServerEntity` |
| `sshPassword` | `String` | 源自 `IspServerEntity` |
| `consolePort` | `Integer` | 源自 `IspServerEntity` |
| `consoleToken` | `String` | 源自 `IspServerEntity` |
| `ldapService` | `Integer` | 源自 `IspServerEntity` |
| `ldapPort` | `Integer` | 源自 `IspServerEntity` |
| `ldapUser` | `String` | 源自 `IspServerEntity` |
| `ldapPassword` | `String` | 源自 `IspServerEntity` |
| `ldapBase` | `String` | 源自 `IspServerEntity` |
| `smbService` | `Integer` | 源自 `IspServerEntity` |
| `smbLdapBase` | `String` | 源自 `IspServerEntity` |
| `smbLdapUser` | `String` | 源自 `IspServerEntity` |
| `smbLdapPassword` | `String` | 源自 `IspServerEntity` |
| `smbLdapUrl` | `String` | 源自 `IspServerEntity` |
| `status` | `Integer` | 源自 `IspServerEntity` |

响应 data 说明：
- 按 `JsonResult<IspServerEntity>` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

#### GET `/isp/server/delete`

- 处理方法：`IspController.deleteServer`
- 响应：[JsonResult](#model-jsonresult)<Void>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `id` | query | yes | Long | 查询/表单参数 |

响应 data 说明：
- 成功时通常无 `data` 或 `data=null`。

#### GET `/isp/server/ssl/refresh`

- 处理方法：`IspController.refreshSSL`
- 响应：[JsonResult](#model-jsonresult)<Void>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `id` | query | yes | Long | 查询/表单参数 |
| `domain` | query | yes | String | 查询/表单参数 |

响应 data 说明：
- 成功时通常无 `data` 或 `data=null`。

#### GET `/isp/domain/record/delete`

- 处理方法：`IspController.refreshSSL`
- 响应：[JsonResult](#model-jsonresult)<Void>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `name` | query | yes | String | 查询/表单参数 |
| `domain` | query | yes | String | 查询/表单参数 |

响应 data 说明：
- 成功时通常无 `data` 或 `data=null`。

#### GET `/isp/dns/hosts`

- 处理方法：`IspController.hosts`
- 响应：[JsonResult](#model-jsonresult)<Void>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `dnsRecordDTO` | query/form | yes | [DnsRecordDTO](#model-dnsrecorddto) | 查询/表单参数 |

响应 data 说明：
- 成功时通常无 `data` 或 `data=null`。

#### ANY `/isp/dns/update_record`

- 处理方法：`IspController.updateDnsRecord`
- 响应：[JsonResult](#model-jsonresult)<expression: request, "isp.dns.update.success">

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `name` | query | yes | String | 查询/表单参数 |

响应 data 说明：
- 按 `JsonResult<expression: request, "isp.dns.update.success">` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

### LdapController `/ldap`

源码：`isp/jia-isp-service/src/main/java/cn/jia/isp/api/LdapController.java`

#### GET `/ldap/user/get`

- 处理方法：`LdapController.userFind`
- 响应：[JsonResult](#model-jsonresult)<LdapUser>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `type` | query | yes | String | 查询/表单参数 |
| `key` | query | yes | String | 查询/表单参数 |

响应 data 说明：
- 按 `JsonResult<LdapUser>` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

#### POST `/ldap/user/search`

- 处理方法：`LdapController.userSearch`
- 响应：[JsonResult](#model-jsonresult)<List<LdapUser>>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `user` | body | yes | LdapUser | 请求体 |

请求体字段：

响应 data 说明：
- 按 `JsonResult<List<LdapUser>>` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

#### POST `/ldap/user/create`

- 处理方法：`LdapController.userCreate`
- 响应：[JsonResult](#model-jsonresult)<LdapUser>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `user` | body | yes | LdapUser | 请求体 |

请求体字段：

响应 data 说明：
- 按 `JsonResult<LdapUser>` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

#### POST `/ldap/user/update`

- 处理方法：`LdapController.userUpdate`
- 响应：[JsonResult](#model-jsonresult)<Void>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `user` | body | yes | LdapUser | 请求体 |

请求体字段：

响应 data 说明：
- 成功时通常无 `data` 或 `data=null`。

#### GET `/ldap/user/delete`

- 处理方法：`LdapController.userDelete`
- 响应：[JsonResult](#model-jsonresult)<Void>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `uid` | query | yes | String | 查询/表单参数 |

响应 data 说明：
- 成功时通常无 `data` 或 `data=null`。

#### GET `/ldap/user/findAll`

- 处理方法：`LdapController.userFindAll`
- 响应：[JsonResult](#model-jsonresult)<Void>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| - | - | - | - | 无入参 |

响应 data 说明：
- 成功时通常无 `data` 或 `data=null`。

#### GET `/ldap/usergroup/findByCn`

- 处理方法：`LdapController.userGroupFindByCn`
- 响应：[JsonResult](#model-jsonresult)<LdapUserGroup>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `cn` | query | yes | String | 查询/表单参数 |

响应 data 说明：
- 按 `JsonResult<LdapUserGroup>` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

#### GET `/ldap/usergroup/findAll`

- 处理方法：`LdapController.userGroupFindAll`
- 响应：[JsonResult](#model-jsonresult)<Void>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| - | - | - | - | 无入参 |

响应 data 说明：
- 成功时通常无 `data` 或 `data=null`。

#### POST `/ldap/usergroup/create`

- 处理方法：`LdapController.userGroupCreate`
- 响应：[JsonResult](#model-jsonresult)<Void>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `userGroup` | body | yes | [LdapUserGroupDTO](#model-ldapusergroupdto) | 请求体 |

请求体字段：

[LdapUserGroupDTO](#model-ldapusergroupdto)

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `dn` | `Name` | 源自 `LdapUserGroupDTO` |
| `cn` | `String` | 源自 `LdapUserGroupDTO` |
| `clientId` | `String` | 源自 `LdapUserGroupDTO` |
| `name` | `String` | 源自 `LdapUserGroupDTO` |
| `logo` | `byte[]` | 源自 `LdapUserGroupDTO` |
| `logoIcon` | `byte[]` | 源自 `LdapUserGroupDTO` |
| `remark` | `String` | 源自 `LdapUserGroupDTO` |
| `description` | `String` | 源自 `LdapUserGroupDTO` |
| `users` | `List<String>` | 源自 `LdapUserGroupDTO` |

响应 data 说明：
- 成功时通常无 `data` 或 `data=null`。

#### POST `/ldap/usergroup/update`

- 处理方法：`LdapController.userGroupUpdate`
- 响应：[JsonResult](#model-jsonresult)<Void>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `userGroup` | body | yes | LdapUserGroup | 请求体 |

请求体字段：

响应 data 说明：
- 成功时通常无 `data` 或 `data=null`。

#### POST `/ldap/usergroup/member/add`

- 处理方法：`LdapController.userGroupMemberAdd`
- 响应：[JsonResult](#model-jsonresult)<Void>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `userGroup` | body | yes | [LdapUserGroupDTO](#model-ldapusergroupdto) | 请求体 |

请求体字段：

[LdapUserGroupDTO](#model-ldapusergroupdto)

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `dn` | `Name` | 源自 `LdapUserGroupDTO` |
| `cn` | `String` | 源自 `LdapUserGroupDTO` |
| `clientId` | `String` | 源自 `LdapUserGroupDTO` |
| `name` | `String` | 源自 `LdapUserGroupDTO` |
| `logo` | `byte[]` | 源自 `LdapUserGroupDTO` |
| `logoIcon` | `byte[]` | 源自 `LdapUserGroupDTO` |
| `remark` | `String` | 源自 `LdapUserGroupDTO` |
| `description` | `String` | 源自 `LdapUserGroupDTO` |
| `users` | `List<String>` | 源自 `LdapUserGroupDTO` |

响应 data 说明：
- 成功时通常无 `data` 或 `data=null`。

#### POST `/ldap/usergroup/member/delete`

- 处理方法：`LdapController.userGroupMemberDelete`
- 响应：[JsonResult](#model-jsonresult)<Void>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `userGroup` | body | yes | [LdapUserGroupDTO](#model-ldapusergroupdto) | 请求体 |

请求体字段：

[LdapUserGroupDTO](#model-ldapusergroupdto)

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `dn` | `Name` | 源自 `LdapUserGroupDTO` |
| `cn` | `String` | 源自 `LdapUserGroupDTO` |
| `clientId` | `String` | 源自 `LdapUserGroupDTO` |
| `name` | `String` | 源自 `LdapUserGroupDTO` |
| `logo` | `byte[]` | 源自 `LdapUserGroupDTO` |
| `logoIcon` | `byte[]` | 源自 `LdapUserGroupDTO` |
| `remark` | `String` | 源自 `LdapUserGroupDTO` |
| `description` | `String` | 源自 `LdapUserGroupDTO` |
| `users` | `List<String>` | 源自 `LdapUserGroupDTO` |

响应 data 说明：
- 成功时通常无 `data` 或 `data=null`。

#### GET `/ldap/usergroup/delete`

- 处理方法：`LdapController.userGroupDelete`
- 响应：[JsonResult](#model-jsonresult)<Void>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `cn` | query | yes | String | 查询/表单参数 |

响应 data 说明：
- 成功时通常无 `data` 或 `data=null`。

#### GET `/ldap/account/findByUid`

- 处理方法：`LdapController.accountFindByUid`
- 响应：[JsonResult](#model-jsonresult)<LdapAccount>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `uid` | query | yes | String | 查询/表单参数 |

响应 data 说明：
- 按 `JsonResult<LdapAccount>` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

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

### Model CarBrandAudiEntity

源码：`isp/jia-isp-core/src/main/java/cn/jia/isp/entity/CarBrandAudiEntity.java`

| 字段 | Java 字段 | 类型 |
| --- | --- | --- |
| `createTime` | `createTime` | `Long` |
| `updateTime` | `updateTime` | `Long` |
| `tenantId` | `tenantId` | `String` |
| `clientId` | `clientId` | `String` |
| `id` | `id` | `Long` |
| `brandMf` | `brandMf` | `Long` |
| `carSeries` | `carSeries` | `String` |
| `abbr` | `abbr` | `String` |
| `initials` | `initials` | `String` |

### Model CarBrandEntity

源码：`isp/jia-isp-core/src/main/java/cn/jia/isp/entity/CarBrandEntity.java`

| 字段 | Java 字段 | 类型 |
| --- | --- | --- |
| `createTime` | `createTime` | `Long` |
| `updateTime` | `updateTime` | `Long` |
| `tenantId` | `tenantId` | `String` |
| `clientId` | `clientId` | `String` |
| `id` | `id` | `Long` |
| `brand` | `brand` | `String` |
| `abbr` | `abbr` | `String` |
| `initials` | `initials` | `String` |
| `addTime` | `addTime` | `Long` |
| `upTime` | `upTime` | `Long` |

### Model CarBrandMfEntity

源码：`isp/jia-isp-core/src/main/java/cn/jia/isp/entity/CarBrandMfEntity.java`

| 字段 | Java 字段 | 类型 |
| --- | --- | --- |
| `createTime` | `createTime` | `Long` |
| `updateTime` | `updateTime` | `Long` |
| `tenantId` | `tenantId` | `String` |
| `clientId` | `clientId` | `String` |
| `id` | `id` | `Long` |
| `brand` | `brand` | `Long` |
| `brandMf` | `brandMf` | `String` |
| `abbr` | `abbr` | `String` |
| `initials` | `initials` | `String` |

### Model CarBrandVersionEntity

源码：`isp/jia-isp-core/src/main/java/cn/jia/isp/entity/CarBrandVersionEntity.java`

| 字段 | Java 字段 | 类型 |
| --- | --- | --- |
| `createTime` | `createTime` | `Long` |
| `updateTime` | `updateTime` | `Long` |
| `tenantId` | `tenantId` | `String` |
| `clientId` | `clientId` | `String` |
| `id` | `id` | `Long` |
| `audi` | `audi` | `Long` |
| `version` | `version` | `String` |
| `vName` | `vName` | `String` |

### Model CmsColumnEntity

源码：`isp/jia-isp-core/src/main/java/cn/jia/isp/entity/CmsColumnEntity.java`

| 字段 | Java 字段 | 类型 |
| --- | --- | --- |
| `createTime` | `createTime` | `Long` |
| `updateTime` | `updateTime` | `Long` |
| `tenantId` | `tenantId` | `String` |
| `clientId` | `clientId` | `String` |
| `id` | `id` | `Long` |
| `tableId` | `tableId` | `Long` |
| `name` | `name` | `String` |
| `type` | `type` | `String` |
| `precision` | `precision` | `Integer` |
| `scale` | `scale` | `Integer` |
| `notnull` | `notnull` | `Integer` |
| `defaultValue` | `defaultValue` | `String` |
| `selectRange` | `selectRange` | `String` |
| `isSearch` | `isSearch` | `Integer` |
| `isList` | `isList` | `Integer` |
| `remark` | `remark` | `String` |

### Model CmsConfigEntity

源码：`isp/jia-isp-core/src/main/java/cn/jia/isp/entity/CmsConfigEntity.java`

| 字段 | Java 字段 | 类型 |
| --- | --- | --- |
| `createTime` | `createTime` | `Long` |
| `updateTime` | `updateTime` | `Long` |
| `tenantId` | `tenantId` | `String` |
| `clientId` | `clientId` | `String` |
| `tablePrefix` | `tablePrefix` | `String` |

### Model CmsRowDTO

源码：`isp/jia-isp-api/src/main/java/cn/jia/isp/entity/CmsRowDTO.java`

| 字段 | Java 字段 | 类型 |
| --- | --- | --- |
| `id` | `id` | `Long` |
| `clientId` | `clientId` | `String` |
| `tableId` | `tableId` | `Long` |
| `tableName` | `tableName` | `String` |
| `rows` | `rows` | `List<CmsRowEntity>` |

### Model CmsRowExample

源码：`isp/jia-isp-api/src/main/java/cn/jia/isp/entity/CmsRowExample.java`

| 字段 | Java 字段 | 类型 |
| --- | --- | --- |
| `id` | `id` | `Long` |
| `clientId` | `clientId` | `String` |
| `name` | `name` | `String` |
| `remark` | `remark` | `String` |
| `rows` | `rows` | `List<CmsRowEntity>` |
| `orderBy` | `orderBy` | `String` |

### Model CmsTableDTO

源码：`isp/jia-isp-api/src/main/java/cn/jia/isp/entity/CmsTableDTO.java`

| 字段 | Java 字段 | 类型 |
| --- | --- | --- |
| `createTime` | `createTime` | `Long` |
| `updateTime` | `updateTime` | `Long` |
| `tenantId` | `tenantId` | `String` |
| `clientId` | `clientId` | `String` |
| `id` | `id` | `Long` |
| `name` | `name` | `String` |
| `remark` | `remark` | `String` |
| `rows` | `rows` | `List<CmsRowEntity>` |
| `columns` | `columns` | `List<CmsColumnDTO>` |

### Model CmsTableEntity

源码：`isp/jia-isp-core/src/main/java/cn/jia/isp/entity/CmsTableEntity.java`

| 字段 | Java 字段 | 类型 |
| --- | --- | --- |
| `createTime` | `createTime` | `Long` |
| `updateTime` | `updateTime` | `Long` |
| `tenantId` | `tenantId` | `String` |
| `clientId` | `clientId` | `String` |
| `id` | `id` | `Long` |
| `name` | `name` | `String` |
| `remark` | `remark` | `String` |

### Model DnsRecordDTO

源码：`isp/jia-isp-api/src/main/java/cn/jia/isp/entity/DnsRecordDTO.java`

| 字段 | Java 字段 | 类型 |
| --- | --- | --- |
| `id` | `id` | `Long` |
| `zoneId` | `zoneId` | `Integer` |
| `zone` | `zone` | `String` |
| `keyName` | `keyName` | `String` |
| `keyValue` | `keyValue` | `String` |
| `domain` | `domain` | `String` |
| `ttl` | `ttl` | `Integer` |
| `type` | `type` | `String` |
| `ip` | `ip` | `String` |
| `historyIp` | `historyIp` | `String` |
| `createTime` | `createTime` | `Date` |
| `lastUpdateTime` | `lastUpdateTime` | `Date` |

### Model IspServerEntity

源码：`isp/jia-isp-core/src/main/java/cn/jia/isp/entity/IspServerEntity.java`

| 字段 | Java 字段 | 类型 |
| --- | --- | --- |
| `createTime` | `createTime` | `Long` |
| `updateTime` | `updateTime` | `Long` |
| `tenantId` | `tenantId` | `String` |
| `clientId` | `clientId` | `String` |
| `id` | `id` | `Long` |
| `serverName` | `serverName` | `String` |
| `serverDescription` | `serverDescription` | `String` |
| `ip` | `ip` | `String` |
| `sshPort` | `sshPort` | `Integer` |
| `sshUser` | `sshUser` | `String` |
| `sshPassword` | `sshPassword` | `String` |
| `consolePort` | `consolePort` | `Integer` |
| `consoleToken` | `consoleToken` | `String` |
| `ldapService` | `ldapService` | `Integer` |
| `ldapPort` | `ldapPort` | `Integer` |
| `ldapUser` | `ldapUser` | `String` |
| `ldapPassword` | `ldapPassword` | `String` |
| `ldapBase` | `ldapBase` | `String` |
| `smbService` | `smbService` | `Integer` |
| `smbLdapBase` | `smbLdapBase` | `String` |
| `smbLdapUser` | `smbLdapUser` | `String` |
| `smbLdapPassword` | `smbLdapPassword` | `String` |
| `smbLdapUrl` | `smbLdapUrl` | `String` |
| `status` | `status` | `Integer` |

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

### Model LdapUserGroupDTO

源码：`isp/jia-isp-api/src/main/java/cn/jia/isp/entity/LdapUserGroupDTO.java`

| 字段 | Java 字段 | 类型 |
| --- | --- | --- |
| `dn` | `dn` | `Name` |
| `cn` | `cn` | `String` |
| `clientId` | `clientId` | `String` |
| `name` | `name` | `String` |
| `logo` | `logo` | `byte[]` |
| `logoIcon` | `logoIcon` | `byte[]` |
| `remark` | `remark` | `String` |
| `description` | `description` | `String` |
| `users` | `users` | `List<String>` |
