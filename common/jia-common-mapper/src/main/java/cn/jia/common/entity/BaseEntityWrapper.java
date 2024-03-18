package cn.jia.common.entity;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;

public interface BaseEntityWrapper<V, T> {
    void appendQueryWrapper(V entity, QueryWrapper<T> wrapper);

    void appendUpdateWrapper(V entity, UpdateWrapper<T> wrapper);
}
