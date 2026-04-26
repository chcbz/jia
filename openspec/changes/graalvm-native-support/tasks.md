# GraalVM Native Image Support Implementation Tasks

## Phase 1: Native Image Configuration Files

### 1.1 Create Native Image Configuration Directory
- [x] Create `src/main/resources/META-INF/native-image/` directory structure
- [x] Add placeholder files for all configuration files

### 1.2 Create reflect-config.json
- [x] Add ClassUtil reflection configuration
- [x] Add BaseDaoImpl reflection configuration  
- [x] Add all Entity classes (scan `**/entity/*.java`)
- [x] Add MyBatis Plus related classes
- [x] Add JSON serialization classes
- [x] Add ErrCodeHolder reflection configuration

### 1.3 Create resource-config.json
- [x] Add Spring configuration includes
- [x] Add static resources patterns
- [x] Add MyBatis mapper XML patterns

### 1.4 Create native-image.properties
```properties
Args = --initialize-at-run-time=com.baomidou.mybatisplus,org.mybatis
```

## Phase 2: Code Modifications

### 2.1 Modify ClassUtil.java
- [x] Add GraalVM mode detection
- [x] Replace dynamic Class.forName with static mapping
- [x] Add GraalVM safe alternatives for class loading

### 2.2 Modify BaseDaoImpl.java
- [x] Add fallback mechanism for wrapper not found
- [x] Ensure backward compatibility with existing code

### 2.3 Modify PayOrderParse.java
- [x] Replace dynamic Class.forName with static Map lookup
- [x] Register all PayOrderParseService implementations at startup
- [x] Use ApplicationContext injection instead of SpringContextHolder

### 2.4 Modify SpringContextHolder.java
- [x] Add GraalVM Native Image mode detection
- [x] Add application context availability check

### 2.5 Modify ErrCodeHolder.java (Zero-Reflection Approach)
- [x] Create @ErrorCodeModule annotation in `cn.jia.core.annotation`
- [x] Create ErrCodeProcessor annotation processor in `cn.jia.core.errcode`
- [x] Modify ErrCodeHolder to support static registration
- [x] Generate ErrCodeRegistry.java at compile time
- [x] Add @ErrorCodeModule to all EsErrorConstants subclasses
- [x] Configure annotationProcessor in build.gradle

### 2.6 ErrCodeHolder Registration Flow
- Compile time: ErrCodeProcessor scans @ErrorCodeModule classes
- Compile time: Generates ErrCodeRegistry.java with register() calls
- Runtime: ErrCodeRegistry.registerAll() registers all error codes
- Zero reflection: No Class.forName or Spring scanning needed

## Phase 3: Build Configuration

### 3.1 Update build.gradle
- [ ] Add Spring Boot Native plugin (需要 Spring Boot 3.x)
- [ ] Configure native-maven-plugin (or native-gradle-plugin)
- [ ] Add native image build task

```groovy
plugins {
    id 'org.springframework.boot' version '3.2.x'
    id 'org.graalvm.buildtools.native' version '0.9.x'
}

native {
    imageName = 'jia-api-native'
}
```

### 3.2 Add Gradle Properties
- [x] Add Gradle Properties configuration
```properties
# gradle.properties
org.gradle.jvmargs=-Xmx4g
spring.native.remove-xml-support=false
```

## Phase 4: Testing

### 4.1 JVM Mode Testing
- [ ] Verify all existing tests pass
- [ ] Verify application starts correctly
- [ ] Test all REST endpoints

### 4.2 Native Image Build Testing
- [ ] Run `./gradlew nativeCompile`
- [ ] Verify native image builds successfully
- [ ] Run native image executable
- [ ] Verify all functionality works

### 4.3 Agent-Based Configuration Generation
- [ ] Run application with `-agentlib:native-image-agent`
- [ ] Generate comprehensive reflect-config.json
- [ ] Merge with manually created configuration

## Phase 5: Third-Party Library Configuration

### 5.1 MyBatis Plus Configuration
- [x] Add entity classes to reflect-config
- [x] Configure TableInfoHelper cache

### 5.2 Druid Configuration
- [x] Add DruidDataSource reflection entries

### 5.3 Redis/Redisson Configuration
- [x] Add Redis client reflection entries

### 5.4 Camunda Workflow Configuration
- [x] Add ProcessEngine related reflection entries

### 5.5 OAuth2/JWT Configuration (2026-04-24)
- [x] Add JwtAuthenticationToken reflection entries
- [x] Add EsSecurityHandler enhancement for native
- [x] Update native-image.properties for JWT initialization

## Summary

| Task | Priority | Estimated Effort |
|------|----------|------------------|
| Native Image Config Files | P0 | 2 hours |
| Code Modifications | P0 | 4 hours |
| Build Configuration | P1 | 1 hour |
| Testing & Verification | P1 | 4 hours |
| Third-Party Libraries | P2 | 2 hours |

**Total Estimated Time**: ~13 hours

## Verification Checklist

- [ ] Application starts in Native Image mode
- [ ] All REST API endpoints respond correctly
- [ ] WebSocket connections work
- [ ] Database CRUD operations function
- [ ] No `NoSuchMethodException` or `ClassNotFoundException`
- [ ] Build size is acceptable (< 100MB)
- [ ] Startup time < 1 second (vs 10+ seconds JVM)

## Modified Files Summary

| File | Description |
|------|-------------|
| `common/jia-common-starter/.../native-image/reflect-config.json` | Native Image 反射配置 |
| `common/jia-common-starter/.../native-image/resource-config.json` | Native Image 资源配置 |
| `common/jia-common-starter/.../native-image/native-image.properties` | Native Image 属性配置 |
| `common/jia-common-core/.../ClassUtil.java` | 添加 GraalVM 模式检测 |
| `common/jia-common-core/.../annotation/ErrorCodeModule.java` | 错误码模块注解 |
| `common/jia-common-core/.../exception/EsErrorConstants.java` | 基类添加 @ErrorCodeModule |
| `common/jia-common-mapper/.../BaseDaoImpl.java` | 更安全的异常处理 |
| `wx/jia-wx-service/.../PayOrderParse.java` | Spring Bean 方式实现 |
| `common/jia-common-service/.../SpringContextHolder.java` | GraalVM 模式标记 |
| `common/jia-common-service/.../ErrCodeHolder.java` | 零反射错误码持有者 |
| `common/jia-common-service/.../ErrCodeProcessor.java` | 编译时注解处理器 |
| `common/jia-common-service/.../javax.annotation.processing.Processor` | SPI 配置 |
| `common/jia-common-starter/.../ErrCodeAutoConfiguration.java` | 错误码自动配置 |
| `gradle.properties` | GraalVM 配置占位符 |
| `**/*ErrorConstants.java` | 各模块错误码类添加 @ErrorCodeModule |
