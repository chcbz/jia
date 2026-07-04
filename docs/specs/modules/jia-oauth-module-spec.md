# OAuth 认证授权模块规格文档

## 模块概述

OAuth 认证授权模块提供OAuth 2.0认证授权功能，包括服务端、客户端和资源服务器。

- **子模块**: api, core, mapper, service, resource, server-starter, client-starter, resource-starter
- **包名**: cn.jia.oauth
- **版本**: 1.1.2-SNAPSHOT

## 核心功能

### 1. OAuth 2.0 服务器

| 功能 | 说明 |
|------|------|
| 授权码模式 | 标准Web应用授权 |
| 密码模式 | 信任应用直接认证 |
| 客户端模式 | 服务间认证 |
| 刷新令牌 | 自动刷新Access Token |
| LDAP集成 | 企业LDAP认证 |

### 2. OAuth 2.0 客户端

| 功能 | 说明 |
|------|------|
| 令牌管理 | Access Token存储和刷新 |
| 用户信息获取 | OAuth用户信息接口 |
| 自动续期 | Token自动刷新 |

### 3. 资源服务器

| 功能 | 说明 |
|------|------|
| Token验证 | 验证请求的Access Token |
| 权限提取 | 从Token提取权限信息 |
| 范围控制 | 资源访问范围限制 |

## 架构设计

```
oauth/
├── jia-oauth-api              # OAuth接口定义
├── jia-oauth-core             # OAuth核心
├── jia-oauth-mapper           # 数据访问
├── jia-oauth-service          # 业务逻辑
├── jia-oauth-resource         # 资源服务器
├── jia-oauth-server-starter   # 服务端Starter
├── jia-oauth-client-starter   # 客户端Starter
└── jia-oauth-resource-starter # 资源Starter
```

## 相关模块

- **jia-common-core**: 提供基础实体
- **jia-user-core**: 用户信息
- **jia-base-core**: 系统配置
