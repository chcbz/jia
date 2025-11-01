package cn.jia.common.service;

import com.github.pagehelper.PageInfo;

import java.io.Serializable;
import java.util.List;

public interface IBaseService<T> {
    T create(T entity);

    T get(Serializable id);

    T update(T entity);

    T upsert(T entity);

    boolean delete(Serializable id);

    T findOne(T query);

    List<T> findList(T query);

    PageInfo<T> findPage(T query, int pageSize, int pageNo);

    PageInfo<T> findPage(T query, int pageSize, int pageNo, String orderBy);
}
