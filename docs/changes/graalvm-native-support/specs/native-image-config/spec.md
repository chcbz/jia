# Native Image Configuration Specification

## Overview

This capability provides the necessary GraalVM Native Image configuration files to ensure the Jia application can run in Native Image mode while maintaining JVM mode compatibility.

## Requirements

### 1. Reflect Configuration

The application must declare all reflection usage in `reflect-config.json`:

- **ClassUtil**: All dynamically loaded classes must be declared
- **BaseDaoImpl**: Entity Wrapper classes must be declared
- **MyBatis Plus**: All entity classes with their fields and methods

```json
[
  {
    "name": "cn.jia.core.util.ClassUtil",
    "allDeclaredMethods": true
  },
  {
    "name": "cn.jia.common.dao.BaseDaoImpl",
    "allDeclaredMethods": true
  }
]
```

### 2. Resource Configuration

Resources must be declared in `resource-config.json`:

- Spring configuration files (`META-INF/spring/**`)
- Static resources (`static/**`, `META-INF/resources/**`)
- MyBatis mapper XML files

### 3. WebSocket Configuration

WebSocket endpoints require special handling via `websocket-config.json`:

- `ChatWebSocketHandler`
- `SmsWebSocketServer`

### 4. Proxy Configuration

Dynamic proxy usage must be declared in `proxy-config.json` (if applicable).

## File Structure

```
src/main/resources/META-INF/native-image/
├── reflect-config.json
├── resource-config.json
├── proxy-config.json
└── native-image.properties
```

## Acceptance Criteria

1. Application starts successfully in Native Image mode
2. All REST endpoints respond correctly
3. WebSocket connections work properly
4. Database operations function correctly
5. No reflection-related runtime exceptions
6. Both JVM and Native Image modes produce identical behavior