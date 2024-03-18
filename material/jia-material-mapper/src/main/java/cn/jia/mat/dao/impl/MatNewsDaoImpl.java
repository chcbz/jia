package cn.jia.mat.dao.impl;

import cn.jia.common.dao.BaseDaoImpl;
import cn.jia.mat.dao.MatNewsDao;
import cn.jia.mat.entity.MatNewsEntity;
import cn.jia.mat.entity.MatNewsReqVO;
import cn.jia.mat.mapper.MatNewsMapper;
import jakarta.inject.Named;

import java.util.List;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author chc
 * @since 2021-10-29
 */
@Named
public class MatNewsDaoImpl extends BaseDaoImpl<MatNewsMapper, MatNewsEntity> implements MatNewsDao {
}
