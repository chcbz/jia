package cn.jia.isp.dao.impl;

import cn.jia.common.dao.BaseDaoImpl;
import cn.jia.isp.dao.IspSmbVDirDao;
import cn.jia.isp.entity.IspSmbVDirEntity;
import cn.jia.isp.mapper.IspSmbVDirMapper;
import jakarta.inject.Named;

@Named
public class IspSmbVDirDaoImpl extends BaseDaoImpl<IspSmbVDirMapper, IspSmbVDirEntity> implements IspSmbVDirDao {
}