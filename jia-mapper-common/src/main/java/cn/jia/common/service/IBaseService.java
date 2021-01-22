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
    List<T> listByEntity(T entity);
}
