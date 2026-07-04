# DWZ 短网址模块规格文档

## 模块概述

DWZ 短网址模块提供长链接转短链接功能。

- **子模块**: api, core, mapper, service, starter
- **包名**: cn.jia.dwz
- **版本**: 1.1.2-SNAPSHOT

## 核心功能

### 1. 短链接生成

| 功能 | 说明 |
|------|------|
| 生成短链 | 长链接转短链接 |
| 批量生成 | 批量生成短链 |
| 自定义 | 自定义短链标识 |
| 有效期 | 设置过期时间 |

### 2. 短链接管理

| 功能 | 说明 |
|------|------|
| 链接查询 | 查询短链接信息 |
| 链接编辑 | 修改链接映射 |
| 链接删除 | 删除短链接 |
| 统计 | 点击统计分析 |

### 3. 访问控制

| 功能 | 说明 |
|------|------|
| 跳转 | 自动跳转原链接 |
| 访问限制 | 访问次数限制 |
| 密码保护 | 访问密码保护 |
| 来源限制 | Referer限制 |

## Service接口

### DwzService

```java
public interface DwzService {
    String create(String longUrl);
    String createWithCustom(String longUrl, String customCode);
    String getLongUrl(String shortCode);
    void delete(String shortCode);
    void updateExpiry(String shortCode, Date expiry);
    DwzEntity getByCode(String shortCode);
    List<DwzEntity> listByUser(String userId);
}
```

### DwzStatService

```java
public interface DwzStatService {
    void recordAccess(String shortCode, String ip, String referer);
    AccessStatEntity getStat(String shortCode);
    List<AccessRecordEntity> listRecords(String shortCode);
}
```

## 相关模块

- **jia-common-core**: 提供基础实体
- **jia-base-core**: 字典功能
