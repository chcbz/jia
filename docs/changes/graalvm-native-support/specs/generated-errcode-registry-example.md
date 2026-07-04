# ErrCodeRegistry 自动生成示例

## 概述

通过编译时注解处理器自动收集所有错误码，生成 `ErrCodeRegistry.java`，实现零反射的错误码注册。

## 核心组件

| 组件 | 位置 | 说明 |
|------|------|------|
| `@ErrorCodeModule` | `cn.jia.core.annotation` | 标记错误码类 |
| `ErrCodeProcessor` | `cn.jia.core.errcode` | 注解处理器 |
| `ErrCodeHolder` | `cn.jia.core.errcode` | 错误码持有者 |
| `ErrCodeRegistry` | `cn.jia.core.errcode` | 编译时生成 |

## 编译后自动生成的代码

当项目编译后，注解处理器会在 `cn.jia.core.errcode` 包下生成 `ErrCodeRegistry.java` 文件：

```java
package cn.jia.core.errcode;

/**
 * 错误码注册中心 - 编译时自动生成
 *
 * 由 ErrCodeProcessor 自动生成，请勿手动修改
 */
public final class ErrCodeRegistry {

    private ErrCodeRegistry() {}

    /**
     * 注册所有错误码
     */
    public static void registerAll() {
        // 基础错误码
        ErrCodeHolder.register("E0", "没有异常");
        ErrCodeHolder.register("_error", "错误键");
        ErrCodeHolder.register("_error_msg", "错误信息");
        ErrCodeHolder.register("_reg_error", "注册错误键");
        ErrCodeHolder.register("E999", "默认错误码");
        ErrCodeHolder.register("E001", "数据不存在");
        ErrCodeHolder.register("E002", "重复提交");
        ErrCodeHolder.register("E003", "参数异常");
        ErrCodeHolder.register("E401", "未授权");
        ErrCodeHolder.register("E403", "拒绝访问");
        ErrCodeHolder.register("E404", "资源不存在");
        ErrCodeHolder.register("E500", "服务异常");
        
        // 用户模块错误码
        ErrCodeHolder.register("U001", "用户不存在");
        ErrCodeHolder.register("U002", "密码错误");
        
        // 微信支付错误码
        ErrCodeHolder.register("WX001", "支付签名失败");
        ErrCodeHolder.register("WX002", "订单不存在");
        
        // ... 其他模块错误码
    }
}
```

## 使用方式

### 1. 在错误码类上添加注解

```java
package cn.jia.user.common;

import cn.jia.core.annotation.ErrorCodeModule;
import cn.jia.core.exception.EsErrorConstants;

/**
 * 用户模块错误码
 */
@ErrorCodeModule("用户模块")
public class UserErrorConstants extends EsErrorConstants {
    
    public UserErrorConstants(String code, String message) {
        super(code, message);
    }
    
    public static final EsErrorConstants USER_NOT_FOUND = new UserErrorConstants("U001", "用户不存在");
    public static final EsErrorConstants PASSWORD_ERROR = new UserErrorConstants("U002", "密码错误");
    public static final EsErrorConstants USER_DISABLED = new UserErrorConstants("U003", "用户已禁用");
}
```

### 2. 配置 annotationProcessor

在 `build.gradle` 中添加：

```groovy
dependencies {
    annotationProcessor project(':common:jia-common-core')
}
```

### 3. 编译项目

```bash
./gradlew compileJava
```

编译时会自动：
1. 扫描所有 `@ErrorCodeModule` 标注的类
2. 收集所有静态 EsErrorConstants 字段
3. 生成 `ErrCodeRegistry.java`
4. 调用 `ErrCodeHolder.register()` 注册所有错误码

### 4. 启动时注册

在应用启动时调用注册方法：

```java
public class Application {
    public static void main(String[] args) {
        ErrCodeRegistry.registerAll();  // 添加这行
        SpringApplication.run(Application.class, args);
    }
}
```

## 优势

| 特性 | 传统方式 | 注解处理器方式 |
|------|----------|----------------|
| 反射使用 | 需要 | 零反射 |
| 启动性能 | 慢（运行时扫描） | 快（编译时生成） |
| GraalVM 兼容 | 需要配置 | 完全兼容 |
| 代码分析 | 困难 | 简单（静态代码） |
| IDE 支持 | 一般 | 好（编译时验证） |

## 模块结构

```
common/jia-common-core/
├── src/main/java/cn/jia/core/
│   ├── annotation/
│   │   └── ErrorCodeModule.java      ← 注解定义
│   └── exception/
│       └── EsErrorConstants.java     ← 基类

common/jia-common-service/
├── src/main/java/cn/jia/core/errcode/
│   ├── ErrCodeHolder.java           ← 错误码持有者
│   └── ErrCodeProcessor.java       ← 注解处理器
├── src/main/resources/META-INF/services/
│   └── javax.annotation.processing.Processor
└── build.gradle
```

## 已添加 @ErrorCodeModule 的模块

| 模块 | 错误码类 | 注解值 |
|------|----------|--------|
| 基础模块 | `EsErrorConstants` | "基础模块" |
| 用户模块 | `UserErrorConstants` | "用户模块" |
| ISP模块 | `IspErrorConstants` | "ISP模块" |
| 微信模块 | `WxErrorConstants` | "微信模块" |
| 客服模块 | `KefuErrorConstants` | "客服模块" |
| 素材模块 | `MatErrorConstants` | "素材模块" |
| 短链接模块 | `DwzErrorConstants` | "短链接模块" |
| 短信模块 | `SmsErrorConstants` | "短信模块" |
| 任务模块 | `TaskErrorConstants` | "任务模块" |
| 积分模块 | `PointErrorConstants` | "积分模块" |
