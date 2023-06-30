package cn.jia.core.service.impl;

import cn.jia.common.service.impl.BaseServiceImpl;
import cn.jia.core.entity.DictEntity;
import cn.jia.core.mapper.DictMapper;
import cn.jia.core.service.IDictService;
import jakarta.inject.Named;

/**
 * <p>
 * 字典数据表 服务实现类
 * </p>
 *
 * @author chc
 * @since 2023-06-18
 */
@Named
public class DictServiceImpl extends BaseServiceImpl<DictMapper, DictEntity> implements IDictService {

}
