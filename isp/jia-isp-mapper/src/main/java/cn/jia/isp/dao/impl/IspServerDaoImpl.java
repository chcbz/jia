package cn.jia.isp.dao.impl;

import cn.jia.common.dao.BaseDaoImpl;
import cn.jia.isp.dao.IspServerDao;
import cn.jia.isp.entity.IspServerEntity;
import cn.jia.isp.mapper.IspServerMapper;
import jakarta.inject.Named;

@Named
public class IspServerDaoImpl extends BaseDaoImpl<IspServerMapper, IspServerEntity> implements IspServerDao {
}