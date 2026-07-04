# Kefu 客服模块规格文档

## 模块概述

Kefu 客服模块提供在线客服功能，支持消息管理、订阅管理和FAQ管理。

- **子模块**: api, core, mapper, service
- **包名**: cn.jia.kefu
- **版本**: 1.1.2-SNAPSHOT

## 核心功能

### 1. 在线客服

| 功能 | 说明 |
|------|------|
| 消息发送 | 发送客服消息 |
| 消息接收 | 接收用户消息 |
| 会话管理 | 客服会话管理 |
| 消息提醒 | 新消息提醒 |

### 2. 消息管理

| 功能 | 说明 |
|------|------|
| 消息记录 | 存储聊天记录 |
| 消息查询 | 查询历史消息 |
| 消息统计 | 消息数据分析 |

### 3. 消息订阅

| 功能 | 说明 |
|------|------|
| 订阅创建 | 创建消息订阅 |
| 订阅管理 | 管理订阅规则 |
| 订阅通知 | 触发消息通知 |

### 4. FAQ管理

| 功能 | 说明 |
|------|------|
| FAQ分类 | FAQ分类管理 |
| FAQ维护 | 常见问题维护 |
| FAQ查询 | 智能FAQ匹配 |

## 实体类

### KefuMessageEntity

客服消息实体，继承 `BaseEntity`：

```java
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@Schema(name = "KefuMessageEntity")
public class KefuMessageEntity extends BaseEntity {
    private String id;             // 消息ID
    private String fromUserId;     // 发送者ID
    private String toUserId;       // 接收者ID
    private String content;        // 消息内容
    private Integer type;          // 消息类型
    private Integer status;        // 状态
}
```

## 相关模块

- **jia-common-core**: 提供基础实体
- **jia-user-core**: 用户信息
- **jia-material-core**: 素材管理
