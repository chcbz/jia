# Jia API 微服务框架规格文档

## 项目概述

Jia 是一个基于 Spring Cloud 的微服务 DevOps 云服务管理框架，提供后台管理 + 服务接口 + 门户首页的全套解决方案。

- **当前版本**: 1.1.2-SNAPSHOT
- **Git仓库**: https://gitee.com/chcbz/jia.git
- **基础包名**: cn.jia

## 技术栈

| 类别 | 技术 |
|------|------|
| 框架 | Spring Cloud |
| 语言 | Java 17+ |
| 构建工具 | Gradle 8.0+ |
| 数据库 | MySQL 5.7+ |
| ORM | MyBatis Plus |
| 缓存 | Redis |
| 工作流 | Camunda |
| 认证 | OAuth 2.0 / Spring Security |
| API文档 | Swagger/OpenAPI 3.0 |

## 模块列表

### 业务模块

| 模块 | 说明 | 包含子模块 |
|------|------|-------------|
| common | 通用模块 | api, core, mapper, service, starter, test |
| base | 基础服务 | api, core, mapper, service, starter |
| user | 用户中心 | api, core, mapper, service, starter |
| oauth | 认证授权 | api, core, mapper, service, resource, server-starter, client-starter, resource-starter |
| workflow | 工作流 | api, core, service, starter |
| kefu | 客服 | api, core, mapper, service |
| material | 素材管理 | api, core, mapper, service, starter |
| sms | 短信 | api, core, mapper, service, starter |
| task | 任务调度 | api, core, mapper, service, starter |
| point | 积分 | api, core, mapper, service |
| isp | ISP服务 | api, core, mapper, service, starter |
| dwz | 短网址 | api, core, mapper, service, starter |
| wx | 微信 | api, core, mapper, service |
| chat | 聊天 | api, core, mapper, service, starter |

### 工具模块

| 模块 | 说明 |
|------|------|
| plugin | Gradle依赖管理插件 |

## 模块分层架构

每个业务模块遵循标准的5层架构：

```
module/
├── jia-{module}-api/          # API层 - Service接口定义
├── jia-{module}-core/         # Core层 - Entity、DTO、常量、枚举
├── jia-{module}-mapper/       # Mapper层 - MyBatis Mapper接口
├── jia-{module}-service/      # Service层 - 业务逻辑实现
└── jia-{module}-starter/     # Starter层 - 自动配置
```

### 包结构规范

```
cn.jia.{module}
├── api/           # API接口
├── service/       # 业务逻辑（impl子包）
├── dao/           # 数据访问（impl子包）
├── entity/        # 实体类
├── dto/           # 数据传输对象
├── mapper/        # MyBatis Mapper
├── config/        # 配置类
├── handler/       # 控制器/Handler
└── common/        # 公共工具类
```

## 核心组件规范

### BaseEntity 基础实体

所有实体类应继承 `BaseEntity`，包含以下公共字段：

```java
public class BaseEntity implements Serializable {
    private Long createTime;    // 创建时间
    private Long updateTime;    // 更新时间
    private String tenantId;    // 租户ID
    private String clientId;    // 应用标识符
}
```

### Service层规范

- Service接口定义在 `*-api` 模块
- Service实现类在 `*-service` 模块
- 使用 `@Service` 注解标记
- 注入依赖使用构造器注入或 `@Autowired`

### DAO层规范

- DAO接口定义在 `*-service` 模块
- DAO实现类在 `*-mapper` 模块
- 使用 `@Repository` 注解标记

### Mapper层规范

- MyBatis Mapper XML文件与Mapper接口在同一包
- 使用MyBatis Plus增强功能

## 构建与发布

### 构建命令

```bash
# 编译项目
./gradlew build

# 安装到本地Maven仓库
./gradlew publishToMavenLocal

# 发布所有模块到本地
./gradlew publishAllToLocal

# 发布所有模块到远程仓库
./gradlew publishAllToMavenRepository
```

### 依赖引入

```groovy
dependencies {
    implementation 'cn.jia:jia-{module}-starter:1.1.2-SNAPSHOT'
}
```

## 相关文档

- [用户模块规格](./modules/jia-user-module-spec.md)
- [认证授权模块规格](./modules/jia-oauth-module-spec.md)
- [工作流模块规格](./modules/jia-workflow-module-spec.md)
- [聊天模块规格](./modules/jia-chat-module-spec.md)
