# Base 基础服务模块规格文档

## 模块概述

Base 基础服务模块提供字典管理、系统日志、通知公告等基础服务功能。

- **子模块**: api, core, mapper, service, starter
- **包名**: cn.jia.base
- **版本**: 1.1.2-SNAPSHOT

## 核心功能

### 1. 字典管理

| 功能 | 说明 |
|------|------|
| 字典类型 | 字典分类管理 |
| 字典项 | 字典数据项 |
| 多语言 | 字典多语言支持 |
| 缓存 | 字典数据缓存 |

### 2. 系统日志

| 功能 | 说明 |
|------|------|
| 日志记录 | 操作日志记录 |
| 日志查询 | 日志查询统计 |
| 日志分析 | 日志数据分析 |

### 3. 通知公告

| 功能 | 说明 |
|------|------|
| 公告发布 | 发布系统公告 |
| 公告管理 | 公告CRUD |
| 公告订阅 | 公告消息推送 |

## Service接口

### DictService

```java
public interface DictService {
    // 字典类型
    DictTypeEntity getType(String code);
    List<DictTypeEntity> listTypes();
    
    // 字典项
    DictItemEntity getItem(String typeCode, String itemCode);
    List<DictItemEntity> listItems(String typeCode);
    
    // 多语言
    String getText(String typeCode, String itemCode, String locale);
}
```

### LogService

```java
public interface LogService {
    void log(String userId, String action, String module, String content);
    List<LogEntity> list(LogQuery query);
    LogEntity getById(String id);
}
```

### NoticeService

```java
public interface NoticeService {
    NoticeEntity create(NoticeEntity notice);
    NoticeEntity publish(String id);
    List<NoticeEntity> listActive();
    void delete(String id);
}
```

## 相关模块

- **jia-common-core**: 提供基础实体和工具类
- **jia-user-core**: 用户信息
