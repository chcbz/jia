## Why

Spring Boot 3 + GraalVM Native Image 可以显著提升应用启动速度并降低内存占用，但需要显式声明反射、资源和代理配置。当前项目使用动态类加载和反射操作，会导致 Native Image 构建失败或运行时异常。

## What Changes

- 添加 GraalVM Native Image 支持所需的反射配置
- 替换动态类加载为显式注册或静态配置
- 配置资源文件访问规则
- 添加 WebSocket 端点动态注册配置
- 配置序列化/反序列化规则

## Capabilities

### New Capabilities

- `native-image-config`: 原生镜像构建配置，包括 reflect-config.json、resource-config.json 等
- `reflection-declarations`: 所有使用反射的类和方法进行显式声明

### Modified Capabilities

- `<none>`: 本次仅做兼容性改造，不涉及业务功能变更

## Non-goals

- 不修改业务逻辑代码（除兼容性必需的改动外）
- 不支持运行时动态类加载（改为启动时注册）
- 不修改 XML 处理的核心逻辑

## Impact

- **构建配置**: 添加 `native-image.args` 参数和配置文件
- **代码修改**: 涉及 `ClassUtil`、`BaseDaoImpl`、`SpringContextHolder` 等工具类
- **依赖**: 需添加 Spring Boot 3 Native 支持相关依赖
- **兼容性**: 需保证 JVM 模式和 Native Image 模式均可正常运行