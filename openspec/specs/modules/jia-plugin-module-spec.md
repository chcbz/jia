# Plugin Gradle插件模块规格文档

## 模块概述

Plugin Gradle插件模块提供依赖管理功能，用于统一管理项目依赖版本。

- **模块**: jia-gradle-plugin
- **包名**: cn.jia.plugin
- **版本**: 1.1.2-SNAPSHOT

## 核心功能

### SharedDependencyManagementPlugin

统一的依赖版本管理插件，在settings.gradle中通过以下方式使用：

```groovy
plugins {
    id 'cn.jia.jia-dependency-management' version '1.1.2-SNAPSHOT'
}
```

### 功能特性

| 功能 | 说明 |
|------|------|
| 版本统一 | 所有模块共享相同的依赖版本 |
| 集中管理 | 在gradle.properties中配置版本 |
| 自动应用 | 子项目自动应用插件 |
| 依赖冲突 | 自动解决依赖版本冲突 |

### 依赖配置

在项目根目录的gradle.properties中配置：

```properties
# 依赖版本号
libs.xxx.version=1.0.0
```

### 使用方式

在子项目的build.gradle中引用：

```groovy
dependencies {
    implementation libs.xxx
}
```
