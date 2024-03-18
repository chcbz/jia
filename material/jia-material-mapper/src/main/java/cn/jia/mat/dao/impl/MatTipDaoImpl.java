package cn.jia.mat.dao.impl;

import cn.jia.common.dao.BaseDaoImpl;
import cn.jia.mat.dao.MatTipDao;
import cn.jia.mat.entity.MatTipEntity;
import cn.jia.mat.mapper.MatTipMapper;
import jakarta.inject.Named;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author chc
 * @since 2021-10-29
 */
@Named
public class MatTipDaoImpl extends BaseDaoImpl<MatTipMapper, MatTipEntity> implements MatTipDao {
}
