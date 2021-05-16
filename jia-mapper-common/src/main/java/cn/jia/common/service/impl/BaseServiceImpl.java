package cn.jia.common.service.impl;

import cn.jia.common.service.IBaseService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;

import java.util.List;

public class BaseServiceImpl<M extends BaseMapper<T>, T> extends ServiceImpl<M, T> implements IBaseService<T> {
    @Override
    public List<T> listByEntity(T entity) {
        QueryWrapper<T> queryWrapper = new QueryWrapper<>(entity);
        return list(queryWrapper);
    }
}
