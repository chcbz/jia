## Context

GraalVM Native Image 要求在编译时完整了解所有运行时行为，包括反射、资源加载和动态代理。当前 Jia 项目使用以下动态特性：

1. **动态类加载** (`ClassUtil.java`): 通过 `Class.forName()` 动态加载类
2. **动态Wrapper生成** (`BaseDaoImpl.java`): 运行时查找 `{Entity}Wrapper` 类
3. **WebSocket端点动态注册**: `@ServerEndpoint` 需要显式注册
4. **反射字段操作**: `ClassUtil` 中的 `setAttribute/getAttribute` 方法

## Goals / Non-Goals

**Goals:**
- 确保应用可在 GraalVM Native Image 模式下正常运行
- 保持 JVM 模式兼容性不变
- 最小化代码侵入性改动

**Non-Goals:**
- 不支持运行时动态类加载（仅支持启动时静态配置）
- 不修改现有业务逻辑
- 不保证所有第三方库的 Native Image 兼容性

## Decisions

### 1. 反射配置声明方式

**决策**: 使用 `reflect-config.json` 声明所有反射使用点

**理由**: 
- 比 `@Reflective` 注解更集中管理
- 可覆盖第三方库无法修改的类
- Spring Boot 3 自动处理大部分场景，仅需补充项目特有的配置

**替代方案考虑**:
- `@Reflective` 注解: 需要修改所有使用反射的类，侵入性大
- 代码注册: 需要在初始化阶段添加大量注册代码

### 2. 动态类加载替换策略

**决策**: 将 `ClassUtil.getClassNames()` 替换为显式注册机制

**修改方案**:
```java
// 原代码 (ClassUtil.java)
Class<?> clazz = Class.forName(className);

// 修改后
@Bean
public List<String> registeredClasses() {
    return List.of(
        "cn.jia.xxx.Entity1",
        "cn.jia.xxx.Entity2"
    );
}
```

### 3. Wrapper 类动态加载

**决策**: 使用编译时生成或服务加载器模式

**方案**:
```java
// 原代码 (BaseDaoImpl.java:173-179)
Class<?> clazz = Class.forName(extendWrapperClass);

// 修改为基于接口的查找
ServiceLoader<BaseEntityWrapper.Factory> loader = 
    ServiceLoader.load(BaseEntityWrapper.Factory.class);
```

### 4. WebSocket 端点配置

**决策**: 添加 `native-image/resource-config.json`

```json
{
  "resources": {
    "includes": ["META-INF/resources/**", "static/**"]
  },
  "bundles": []
}
```

### 5. MyBatis Plus 配置

**决策**: 添加 MyBatis Plus 专用的 native-image 配置

**理由**: MyBatis Plus 使用反射访问实体类信息，需要声明所有实体类的字段访问权限

### 6. 错误码零反射方案

**决策**: 使用编译时注解处理器生成 ErrCodeRegistry

**实现**:

```java
// 1. 定义注解
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.TYPE)
public @interface ErrorCodeModule {
    String value() default "";
}

// 2. 注解处理器
@SupportedAnnotationTypes("cn.jia.core.annotation.ErrorCodeModule")
public class ErrCodeProcessor extends AbstractProcessor {
    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        // 收集所有 @ErrorCodeModule 类的静态字段
        // 生成 ErrCodeRegistry.java
    }
}

// 3. 生成代码
public final class ErrCodeRegistry {
    public static void registerAll() {
        ErrCodeHolder.register("E0", "没有异常");
        ErrCodeHolder.register("E001", "数据不存在");
        // ...
    }
}

// 4. 使用
ErrCodeRegistry.registerAll();  // 启动时调用
```

**优势**:
- 编译时完成所有错误码收集
- 无需运行时反射扫描
- Native Image 完全兼容

## Risks / Trade-offs

| 风险 | 影响 | 缓解措施 |
|------|------|----------|
| 第三方库不支持 Native Image | 某些依赖库可能无法编译 | 评估并替换不兼容库；使用 `-H:+Include` 排除 |
| 遗漏的反射使用点 | 运行时报 `ClassNotFoundException` | 使用 Agent 模式进行完整测试 |
| 启动性能下降 | Native Image 构建时间增加 | 使用 Gradle 缓存和并行构建 |
| 类加载器问题 | 某些动态加载场景失效 | 迁移到静态配置模式 |

## Migration Plan

1. **阶段1 - 配置创建**
   - 创建 `native-image` 目录
   - 添加 `reflect-config.json` 基础配置
   - 添加 `resource-config.json`
   - 添加 `proxy-config.json`（如需要）

2. **阶段2 - 代码改造**
   - 修改 `ClassUtil` 移除动态类加载
   - 修改 `BaseDaoImpl` 使用 ServiceLoader 模式
   - 修改 `PayOrderParse` 使用静态映射

3. **阶段3 - 测试验证**
   - 使用 `native-image-agent` 生成完整配置
   - 运行集成测试
   - 验证所有功能正常

4. **阶段4 - 构建优化**
   - 配置 Gradle native-maven-plugin
   - 添加构建参数
   - 优化镜像大小

## Open Questions

1. **Chat 模块中的动态类型加载**: `ChatClient` 相关的泛型处理是否需要特殊配置？
2. **Camunda Workflow**: 工作流引擎的反射使用是否完整声明？
3. **Redis 序列化**: 自定义序列化器是否需要额外配置？
4. **LDAP 集成**: LDAP 模板的 JNDI 查找是否兼容？

## 配置文件清单

### 1. reflect-config.json (示例)

```json
[
  {
    "name": "cn.jia.common.entity.BaseEntity",
    "fields": [
      {"name": "createTime"},
      {"name": "updateTime"}
    ]
  },
  {
    "name": "cn.jia.wx.entity.PayOrderEntity",
    "allDeclaredFields": true,
    "allDeclaredMethods": true
  }
]
```

### 2. resource-config.json

```json
{
  "resources": {
    "includes": [
      "META-INF/spring/**",
      "META-INF/resources/**",
      "static/**"
    ]
  }
}
```

### 3. 所需依赖

```groovy
// build.gradle
nativeBeta {
    imageName = 'jia-api'
}
```

## 实现优先级

1. **P0**: 核心反射配置（ClassUtil、BaseDaoImpl）
2. **P1**: WebSocket 和 Web 配置
3. **P1**: MyBatis Plus 实体配置
4. **P2**: 第三方库配置（Druid、Redisson 等）