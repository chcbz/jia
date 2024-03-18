package cn.jia.base.dao.impl;

import cn.jia.base.dao.DictDao;
import cn.jia.base.entity.DictEntity;
import cn.jia.base.mapper.DictMapper;
import cn.jia.common.dao.BaseDaoImpl;
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
public class DictDaoImpl extends BaseDaoImpl<DictMapper, DictEntity> implements DictDao {

}
