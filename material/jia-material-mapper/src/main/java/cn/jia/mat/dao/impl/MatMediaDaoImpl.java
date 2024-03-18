package cn.jia.mat.dao.impl;

import cn.jia.common.dao.BaseDaoImpl;
import cn.jia.mat.dao.MatMediaDao;
import cn.jia.mat.entity.MatMediaEntity;
import cn.jia.mat.mapper.MatMediaMapper;
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
public class MatMediaDaoImpl extends BaseDaoImpl<MatMediaMapper, MatMediaEntity> implements MatMediaDao {
}
