# SMS 短信模块规格文档

## 模块概述

SMS 短信模块提供短信发送和记录管理功能。

- **子模块**: api, core, mapper, service, starter
- **包名**: cn.jia.sms
- **版本**: 1.1.2-SNAPSHOT

## 核心功能

### 1. 短信发送

| 功能 | 说明 |
|------|------|
| 单发 | 单个手机号发送 |
| 群发 | 批量手机号发送 |
| 模板短信 | 模板变量替换 |
| 验证码 | 短信验证码发送 |

### 2. 短信记录

| 功能 | 说明 |
|------|------|
| 发送记录 | 短信发送日志 |
| 状态查询 | 发送状态回执 |
| 计费统计 | 短信计费统计 |

### 3. 短信模板

| 功能 | 说明 |
|------|------|
| 模板管理 | 短信模板CRUD |
| 模板审核 | 模板审核流程 |
| 变量配置 | 模板变量配置 |

## Service接口

### SmsService

```java
public interface SmsService {
    // 发送
    SmsResultEntity send(String phone, String content);
    SmsResultEntity sendTemplate(String phone, String templateId, Map<String, String> params);
    
    // 查询
    SmsRecordEntity getRecord(String id);
    List<SmsRecordEntity> listRecords(SmsQuery query);
    
    // 模板
    SmsTemplateEntity createTemplate(SmsTemplateEntity template);
    List<SmsTemplateEntity> listTemplates();
}
```

## 相关模块

- **jia-common-core**: 提供基础实体
- **jia-user-core**: 用户手机号
