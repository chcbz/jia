package cn.jia.common.service;

import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

/**
 * <p>
 * 基础Service类
 * </p>
 *
 * @author chc
 * @since 2021-01-16
 */
public interface IBaseService<T> extends IService<T> {
    /**
     * 以实体属性作为过滤条件
     *
     * @param entity 实体
     * @return 过滤结果
     */
    List<T> listByEntity(T entity);
}
