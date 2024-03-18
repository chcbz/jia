package cn.jia.core.service;

import cn.jia.common.service.IBaseService;
import cn.jia.core.dao.IBaseDao;
import cn.jia.core.entity.BaseEntity;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import jakarta.inject.Inject;
import cn.jia.core.util.CollectionUtil;

import java.io.Serializable;
import java.util.List;
import java.util.function.Function;

public abstract class BaseServiceImpl<D extends IBaseDao<T>, T extends BaseEntity> implements IBaseService<T> {
    @Inject
    protected D baseDao;

    @Override
    public T create(T entity) {
        return retEntity(baseDao::insert, entity);
    }

    @Override
    public T get(Serializable id) {
        return baseDao.selectById(id);
    }

    @Override
    public T update(T entity) {
        return retEntity(baseDao::updateById, entity);
    }

    @Override
    public T upsert(T entity) {
        return retEntity(baseDao::insertOrUpdate, entity);
    }

    @Override
    public boolean delete(Serializable id) {
        return retBool(baseDao.deleteById(id));
    }

    @Override
    public T findOne(T query) {
        List<T> list = baseDao.selectByEntity(query);
        return CollectionUtil.isNullOrEmpty(list) ? null : list.get(0);
    }

    @Override
    public List<T> findList(T query) {
        return baseDao.selectByEntity(query);
    }

    @Override
    public PageInfo<T> findPage(T query, int pageSize, int pageNo) {
        PageHelper.startPage(pageNo, pageSize);
        return PageInfo.of(baseDao.selectByEntity(query));
    }

    protected Boolean retBool(Integer result) {
        return null != result && result > 0;
    }

    protected T retEntity(Function<T, Integer> function, T entity) {
        return function.apply(entity) > 0 ? entity : null;
    }
}
