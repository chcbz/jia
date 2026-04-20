# User 用户中心模块规格文档

## 模块概述

User 用户中心模块提供用户、角色、组织和权限管理功能。

- **子模块**: api, core, mapper, service, starter
- **包名**: cn.jia.user
- **版本**: 1.1.2-SNAPSHOT

## 核心功能

### 1. 用户管理

| 功能 | 说明 |
|------|------|
| 用户注册 | 创建新用户账号 |
| 用户登录 | 身份认证 |
| 用户信息管理 | 更新用户资料 |
| 密码管理 | 修改密码 |
| 手机号管理 | 绑定/更换手机号 |
| OpenID管理 | 第三方登录绑定 |

### 2. 角色管理

| 功能 | 说明 |
|------|------|
| 角色创建 | 创建系统角色 |
| 角色分配 | 为用户分配角色 |
| 角色权限 | 角色关联权限 |

### 3. 组织管理

| 功能 | 说明 |
|------|------|
| 组织结构 | 多级组织架构 |
| 组织成员 | 用户归属组织 |
| 组织权限 | 基于组织的权限控制 |

### 4. 分组管理

| 功能 | 说明 |
|------|------|
| 用户分组 | 按业务分组用户 |
| 分组权限 | 分组级别权限 |

### 5. 权限管理

| 功能 | 说明 |
|------|------|
| 权限定义 | 系统权限点 |
| 权限分配 | 分配给角色/用户 |
| 权限验证 | 运行时权限检查 |

### 6. 积分管理

| 功能 | 说明 |
|------|------|
| 积分获取 | 多种积分来源 |
| 积分消费 | 积分使用 |
| 积分查询 | 积分记录查询 |

## 实体类

### UserEntity

用户实体，继承 `BaseEntity`：

```java
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@Schema(name = "UserEntity")
public class UserEntity extends BaseEntity {
    private String id;              // 用户ID
    private String username;       // 用户名
    private String password;        // 密码
    private String phone;          // 手机号
    private String email;          // 邮箱
    private String openId;         // 第三方OpenID
    private Integer status;        // 状态
    private Long points;           // 积分
}
```

### RoleEntity

角色实体：

```java
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@Schema(name = "RoleEntity")
public class RoleEntity extends BaseEntity {
    private String id;             // 角色ID
    private String name;           // 角色名称
    private String description;    // 描述
    private Integer status;        // 状态
}
```

### OrgEntity

组织实体：

```java
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@Schema(name = "OrgEntity")
public class OrgEntity extends BaseEntity {
    private String id;             // 组织ID
    private String name;           // 组织名称
    private String parentId;        // 父组织ID
    private Integer level;         // 层级
    private String path;           // 路径
}
```

## Service接口

### UserService

```java
public interface UserService {
    UserEntity create(UserEntity user);
    UserEntity getById(String id);
    UserEntity getByUsername(String username);
    UserEntity getByPhone(String phone);
    void update(UserEntity user);
    void delete(String id);
    List<UserEntity> list(UserEntity condition);
    boolean changePassword(String userId, String oldPassword, String newPassword);
}
```

### RoleService

```java
public interface RoleService {
    RoleEntity create(RoleEntity role);
    RoleEntity getById(String id);
    void update(RoleEntity role);
    void delete(String id);
    List<RoleEntity> listAll();
    void assignRoles(String userId, List<String> roleIds);
}
```

### OrgService

```java
public interface OrgService {
    OrgEntity create(OrgEntity org);
    OrgEntity getById(String id);
    void update(OrgEntity org);
    void delete(String id);
    List<OrgEntity> listByParentId(String parentId);
    List<OrgEntity> listTree();
}
```

## 模块依赖

```
user/
├── jia-user-api         # 接口定义
├── jia-user-core        # 实体、DTO、枚举
├── jia-user-mapper      # 数据访问实现
├── jia-user-service     # 业务逻辑
└── jia-user-starter    # 自动配置
```

## 相关模块

- **jia-common-core**: 提供 BaseEntity
- **jia-common-mapper**: 提供 BaseDaoImpl
- **jia-oauth-core**: OAuth认证核心
- **jia-point-core**: 积分核心
