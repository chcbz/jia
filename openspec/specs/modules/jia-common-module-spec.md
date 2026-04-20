# Common 通用模块规格文档

## 模块概述

Common 通用模块提供框架级的基础功能，被所有其他模块依赖。

- **子模块**: api, core, mapper, service, starter, test
- **包名**: cn.jia.core
- **版本**: 1.1.2-SNAPSHOT

## 核心组件

### BaseEntity 基础实体

所有业务实体的基类，提供公共字段：

```java
public class BaseEntity implements Serializable {
    private Long createTime;    // 创建时间
    private Long updateTime;    // 更新时间
    private String tenantId;    // 租户ID
    private String clientId;    // 应用标识符
}
```

### BaseDaoImpl 通用DAO实现

提供基于MyBatis Plus的通用数据访问实现：

```java
public class BaseDaoImpl<T> {
    // CRUD基础操作
    public T selectById(Serializable id);
    public List<T> selectList(wrapper);
    public int insert(T entity);
    public int updateById(T entity);
    public int deleteById(Serializable id);
}
```

### DateUtil 日期工具

时间处理工具类：

```java
public class DateUtil {
    public static Long nowTime();           // 获取当前时间戳
    public static Date nowDate();           // 获取当前日期
    public static String format(Long time); // 格式化时间
}
```

## 通用功能

| 功能 | 说明 |
|------|------|
| 分页查询 | 通用分页工具 |
| 条件构造 | QueryWrapper构造器 |
| 字典转换 | 数据字典转换 |
| 异常处理 | 统一异常处理 |
| 结果封装 | 通用响应封装 |

## 模块结构

```
common/
├── jia-common-api         # 通用接口定义
├── jia-common-core        # 实体、工具类
├── jia-common-mapper      # 通用DAO
├── jia-common-service     # 通用业务
├── jia-common-starter     # 自动配置
└── jia-common-test       # 测试工具
```
