# Jia 微服务 DevOps 云服务管理框架

## 项目简介

Jia 是一个基于 Spring Cloud 的微服务 DevOps 云服务管理框架，提供后台管理 + 服务接口+门户首页的全套解决方案。框架采用模块化设计，包含多个业务模块和通用组件，支持快速开发和部署企业级应用。

## 技术栈

### 后端技术
- **Spring Boot** - 应用框架基础
- **Spring Cloud** - 微服务架构
- **Spring Security** - 安全认证
- **MyBatis** - 持久层框架
- **Camunda** - 工作流引擎
- **Redis** - 缓存服务
- **MySQL** - 关系型数据库

### 前端技术
- **Vue.js** - 前端框架
- **HTML/CSS/JavaScript** - 页面开发

### 构建工具
- **Gradle** - 项目构建和依赖管理
- **Maven** - 发布管理

## 项目结构

```
jia/
├── common/          # 通用模块
│   ├── jia-common-api       # 通用 API 接口
│   ├── jia-common-core      # 通用核心功能
│   ├── jia-common-mapper    # 通用数据访问层
│   ├── jia-common-service   # 通用业务逻辑层
│   ├── jia-common-starter   # 通用自动配置
│   └── jia-common-test      # 通用测试工具
├── base/            # 基础服务模块
│   ├── jia-base-core        # 基础核心
│   ├── jia-base-api         # 基础 API（字典、日志、通知）
│   ├── jia-base-mapper      # 基础数据访问
│   ├── jia-base-service     # 基础业务逻辑
│   └── jia-base-starter     # 基础自动配置
├── user/            # 用户中心模块
│   ├── jia-user-core        # 用户核心
│   ├── jia-user-api         # 用户 API（用户、角色、组织、权限）
│   ├── jia-user-mapper      # 用户数据访问
│   ├── jia-user-service     # 用户业务逻辑
│   └── jia-user-starter     # 用户自动配置
├── oauth/           # 认证授权模块
│   ├── jia-oauth-core       # OAuth 核心
│   ├── jia-oauth-api        # OAuth API
│   ├── jia-oauth-mapper     # OAuth 数据访问
│   ├── jia-oauth-service    # OAuth 业务逻辑
│   ├── jia-oauth-resource   # OAuth 资源服务
│   ├── jia-oauth-server-starter    # OAuth 服务端配置
│   ├── jia-oauth-client-starter    # OAuth 客户端配置
│   └── jia-oauth-resource-starter  # OAuth 资源配置
├── workflow/        # 工作流模块
│   ├── jia-workflow-core    # 工作流核心
│   ├── jia-workflow-api     # 工作流 API
│   ├── jia-workflow-service # 工作流业务逻辑
│   └── jia-workflow-starter # 工作流自动配置
├── kefu/            # 客服模块
│   ├── jia-kefu-core        # 客服核心
│   ├── jia-kefu-api         # 客服 API（消息、订阅、FAQ）
│   ├── jia-kefu-mapper      # 客服数据访问
│   └── jia-kefu-service     # 客服业务逻辑
├── material/        # 素材管理模块
│   ├── jia-material-core    # 素材核心
│   ├── jia-material-api     # 素材 API（媒体、新闻、短语、投票）
│   ├── jia-material-mapper  # 素材数据访问
│   ├── jia-material-service # 素材业务逻辑
│   └── jia-material-starter # 素材自动配置
├── sms/             # 短信模块
│   ├── jia-sms-core         # 短信核心
│   ├── jia-sms-api          # 短信 API
│   ├── jia-sms-mapper       # 短信数据访问
│   ├── jia-sms-service      # 短信业务逻辑
│   └── jia-sms-starter      # 短信自动配置
├── task/            # 任务调度模块
│   ├── jia-task-core        # 任务核心
│   ├── jia-task-api         # 任务 API
│   ├── jia-task-mapper      # 任务数据访问
│   ├── jia-task-service     # 任务业务逻辑
│   └── jia-task-starter     # 任务自动配置
├── point/           # 积分模块
│   ├── jia-point-core       # 积分核心
│   ├── jia-point-api        # 积分 API
│   ├── jia-point-mapper     # 积分数据访问
│   └── jia-point-service    # 积分业务逻辑
├── isp/             # ISP 服务模块
│   ├── jia-isp-core         # ISP 核心
│   ├── jia-isp-api          # ISP API
│   ├── jia-isp-mapper       # ISP 数据访问
│   ├── jia-isp-service      # ISP 业务逻辑
│   └── jia-isp-starter      # ISP 自动配置
├── dwz/             # 短网址模块
│   ├── jia-dwz-core         # 短网址核心
│   ├── jia-dwz-api          # 短网址 API
│   ├── jia-dwz-mapper       # 短网址数据访问
│   ├── jia-dwz-service      # 短网址业务逻辑
│   └── jia-dwz-starter      # 短网址自动配置
├── wx/              # 微信模块
│   ├── jia-wx-core          # 微信核心
│   ├── jia-wx-api           # 微信 API
│   ├── jia-wx-mapper        # 微信数据访问
│   └── jia-wx-service       # 微信业务逻辑
├── mcp/             # MCP 模块
│   ├── jia-mcp-client       # MCP 客户端
│   ├── jia-mcp-client-starter # MCP 客户端配置
│   └── jia-mcp-server       # MCP 服务端
├── plugin/          # Gradle 插件模块
│   └── jia-gradle-plugin    # 依赖管理插件
├── build.gradle     # 根构建配置
├── settings.gradle  # 项目设置
└── gradle.properties # Gradle 属性配置
```

## 核心功能

### 1. 用户管理
- 用户信息管理（账号、密码、手机号、OpenID）
- 角色管理（多角色支持）
- 组织管理（多级组织结构）
- 分组管理
- 权限管理
- 积分管理

### 2. 认证授权（OAuth 2.0）
- OAuth 2.0 服务器
- OAuth 2.0 客户端
- 资源服务器
- LDAP 集成

### 3. 工作流管理（Camunda）
- 流程定义管理
- 流程部署
- 任务管理（创建、分配、完成）
- 流程实例管理
- 历史任务查询
- 流程变量管理
- 任务批注

### 4. 基础服务
- 字典管理（多语言支持）
- 系统日志
- 通知公告

### 5. 客服系统
- 在线客服
- 消息管理
- 消息订阅
- FAQ 管理

### 6. 素材管理
- 媒体素材管理
- 新闻素材管理
- 常用短语
- 投票管理
- PV 统计

### 7. 短信服务
- 短信发送
- 短信记录管理

### 8. 任务调度
- 定时任务管理
- 任务执行日志

### 9. 其他服务
- 短网址生成（DWZ）
- ISP 服务管理
- 微信公众号集成
- MCP（Model Context Protocol）支持

## 快速开始

### 环境要求
- JDK 17+
- Gradle 8.0+
- MySQL 5.7+
- Redis 5.0+

### 构建项目

```bash
# 克隆项目
git clone <repository-url>
cd jia/api

# 编译项目
./gradlew build

# 安装到本地 Maven 仓库
./gradlew publishToMavenLocal

# 发布所有模块到本地
./gradlew publishAllToLocal

# 发布所有模块到远程仓库
./gradlew publishAllToMavenRepository
```

### 依赖配置

在您的项目的 `build.gradle` 中添加：

```groovy
dependencies {
    // 示例：引入用户模块
    implementation 'cn.jia:jia-user-starter:1.1.2-SNAPSHOT'
    
    // 示例：引入工作流模块
    implementation 'cn.jia:jia-workflow-starter:1.1.2-SNAPSHOT'
    
    // 示例：引入 OAuth 模块
    implementation 'cn.jia:jia-oauth-server-starter:1.1.2-SNAPSHOT'
}
```

## 模块说明

### 分层架构
每个模块都遵循以下分层架构：
- **API 层**: 对外暴露的服务接口
- **Core 层**: 实体类、常量、枚举等核心定义
- **Mapper 层**: 数据访问层（MyBatis Mapper）
- **Service 层**: 业务逻辑层
- **Starter 层**: 自动配置和启动器

### 版本号
当前版本：`1.1.2-SNAPSHOT`

### 包命名规范
- 基础包名：`cn.jia`
- 模块包名：`cn.jia.{module}`
- 例如：`cn.jia.user.service`, `cn.jia.workflow.service`

## 开发指南

### 添加新模块
1. 在对应目录下创建模块文件夹
2. 按照标准分层结构创建子模块
3. 在 `settings.gradle` 中添加模块引用
4. 配置 `build.gradle` 依赖

### 自定义扩展
- 继承或实现各模块的 Service 接口
- 使用 Starter 进行自动配置
- 通过配置文件自定义参数

## 许可证

本项目采用 MIT 开源许可证。详见 [LICENSE](LICENSE) 文件。

## 联系方式

- Copyright (c) 2020 Richow
- 项目组织：cn.jia

## 更新日志

### v1.1.2-SNAPSHOT
- 优化 Gradle 插件依赖管理
- 完善 OAuth 2.0 认证授权
- 增强工作流引擎功能
- 改进模块化结构设计