package cn.jia.common.dao;

import cn.jia.common.entity.BaseEntityWrapper;
import cn.jia.core.dao.IBaseDao;
import cn.jia.core.entity.BaseEntity;
import cn.jia.core.util.CollectionUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.enums.SqlMethod;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.TableInfo;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.core.toolkit.*;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.baomidou.mybatisplus.extension.toolkit.SqlHelper;
import jakarta.inject.Inject;
import org.apache.ibatis.binding.MapperMethod;
import org.apache.ibatis.logging.Log;
import org.apache.ibatis.logging.LogFactory;
import org.apache.ibatis.session.SqlSession;

import java.io.Serializable;
import java.lang.reflect.Constructor;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;

/**
 * <p>
 * 基础Dao实现类
 * </p>
 *
 * @author chc
 * @since 2023-08-05
 */
public abstract class BaseDaoImpl<M extends BaseMapper<T>, T extends BaseEntity> implements IBaseDao<T> {

    protected Log log = LogFactory.getLog(getClass());

    @Inject
    protected M baseMapper;

    protected Class<M> mapperClass = currentMapperClass();

    protected Class<T> entityClass = currentModelClass();

    protected Class<M> currentMapperClass() {
        return (Class<M>) ReflectionKit.getSuperClassGenericType(this.getClass(), BaseDaoImpl.class, 0);
    }

    protected Class<T> currentModelClass() {
        return (Class<T>) ReflectionKit.getSuperClassGenericType(this.getClass(), ServiceImpl.class, 1);
    }

    @Override
    public int insert(T entity) {
        entity.init4Creation();
        return baseMapper.insert(entity);
    }

    @Override
    public boolean insertBatch(Collection<T> entityList, int batchSize) {
        String sqlStatement = getSqlStatement(SqlMethod.INSERT_ONE);
        return executeBatch(entityList, batchSize, (sqlSession, entity) -> {
            entity.init4Creation();
            sqlSession.insert(sqlStatement, entity);
        });
    }

    @Override
    public boolean insertOrUpdateBatch(Collection<T> entityList, int batchSize) {
        TableInfo tableInfo = TableInfoHelper.getTableInfo(entityClass);
        Assert.notNull(tableInfo, "error: can not execute. because can not find cache of TableInfo for entity!");
        String keyProperty = tableInfo.getKeyProperty();
        Assert.notEmpty(keyProperty, "error: can not execute. because can not find column for id from entity!");
        return saveOrUpdateBatch(entityList, batchSize, (sqlSession, entity) -> {
            Object idVal = tableInfo.getPropertyValue(entity, keyProperty);
            return StringUtils.checkValNull(idVal)
                    || CollectionUtil.isNullOrEmpty(sqlSession.selectList(getSqlStatement(SqlMethod.SELECT_BY_ID), entity));
        }, (sqlSession, entity) -> {
            entity.init4Update();
            MapperMethod.ParamMap<T> param = new MapperMethod.ParamMap<>();
            param.put(Constants.ENTITY, entity);
            sqlSession.update(getSqlStatement(SqlMethod.UPDATE_BY_ID), param);
        });
    }

    protected boolean saveOrUpdateBatch(Collection<T> list, int batchSize, BiPredicate<SqlSession, T> predicate,
            BiConsumer<SqlSession, T> consumer) {
        String sqlStatement = getSqlStatement(SqlMethod.INSERT_ONE);
        return executeBatch(list, batchSize, (sqlSession, entity) -> {
            if (predicate.test(sqlSession, entity)) {
                entity.init4Creation();
                sqlSession.insert(sqlStatement, entity);
            } else {
                consumer.accept(sqlSession, entity);
            }
        });
    }

    @Override
    public int deleteById(Serializable id) {
        return baseMapper.deleteById(id);
    }

    @Override
    public int deleteBatchIds(Collection<?> idList) {
        return baseMapper.deleteBatchIds(idList);
    }

    @Override
    public int updateById(T entity) {
        entity.init4Update();
        return baseMapper.updateById(entity);
    }

    @Override
    public boolean updateBatchById(Collection<T> entityList, int batchSize) {
        String sqlStatement = getSqlStatement(SqlMethod.UPDATE_BY_ID);
        return executeBatch(entityList, batchSize, (sqlSession, entity) -> {
            entity.init4Update();
            MapperMethod.ParamMap<T> param = new MapperMethod.ParamMap<>();
            param.put(Constants.ENTITY, entity);
            sqlSession.update(sqlStatement, param);
        });
    }

    @Override
    public int insertOrUpdate(T entity) {
        if (null == entity) {
            return 0;
        }
        TableInfo tableInfo = TableInfoHelper.getTableInfo(this.entityClass);
        Assert.notNull(tableInfo, "error: can not execute. because can not find cache of TableInfo for entity!");
        String keyProperty = tableInfo.getKeyProperty();
        Assert.notEmpty(keyProperty, "error: can not execute. because can not find column for id from entity!");
        Object idVal = tableInfo.getPropertyValue(entity, tableInfo.getKeyProperty());
        if (StringUtils.checkValNull(idVal) || Objects.isNull(selectById((Serializable) idVal))) {
            entity.init4Creation();
            return insert(entity);
        } else {
            entity.init4Update();
            return updateById(entity);
        }
    }

    @Override
    public T selectById(Serializable id) {
        return baseMapper.selectById(id);
    }

    @Override
    public List<T> selectBatchIds(Collection<? extends Serializable> idList) {
        return baseMapper.selectBatchIds(idList);
    }

    @Override
    public List<T> selectByMap(Map<String, Object> columnMap) {
        return baseMapper.selectByMap(columnMap);
    }

    @Override
    public List<T> selectByEntity(T entity) {
        QueryWrapper<T> queryWrapper = new QueryWrapper<>(entity);
        appendQueryWrapper(entity, queryWrapper);
        return baseMapper.selectList(queryWrapper);
    }

    protected void appendQueryWrapper(T entity, QueryWrapper<T> queryWrapper) {
        try {
            String extendWrapperClass = entity.getClass().getName() + "Wrapper";
            // GraalVM Native Image 兼容: 使用更安全的类加载方式
            // reflect-config.json 中已配置 BaseDaoImpl 的反射访问
            Class<?> clazz = Class.forName(extendWrapperClass);
            Constructor<?> constructor = clazz.getDeclaredConstructor();
            constructor.setAccessible(true);
            BaseEntityWrapper<T, T> entityWrapper = (BaseEntityWrapper) constructor.newInstance();
            entityWrapper.appendQueryWrapper(entity, queryWrapper);
        } catch (ClassNotFoundException | NoSuchMethodException ignored) {
            // Wrapper 类不存在或无默认构造函数时静默忽略，保持向后兼容
        } catch (Exception e) {
            // 其他异常静默忽略
        }
    }

    @Override
    public List<T> selectAll() {
        return baseMapper.selectList(null);
    }

    /**
     * 获取mapperStatementId
     *
     * @param sqlMethod 方法名
     * @return 命名id
     * @since 3.4.0
     */
    protected String getSqlStatement(SqlMethod sqlMethod) {
        return SqlHelper.getSqlStatement(mapperClass, sqlMethod);
    }

    /**
     * 执行批量操作
     *
     * @param list      数据集合
     * @param batchSize 批量大小
     * @param consumer  执行方法
     * @return 操作结果
     * @since 3.3.1
     */
    protected boolean executeBatch(Collection<T> list, int batchSize, BiConsumer<SqlSession, T> consumer) {
        return SqlHelper.executeBatch(this.entityClass, log, list, batchSize, consumer);
    }
}
